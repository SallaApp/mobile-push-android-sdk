package com.salla.push.internal.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.salla.push.internal.engine.Credentials
import com.salla.push.internal.log.SecretString
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets. Any failure returns null; the caller then resets and registers again. */
internal interface SecretBox {
    fun seal(plain: ByteArray): ByteArray?

    fun open(sealed: ByteArray): ByteArray?

    /** Deletes the key, for example after a restore or an OEM keystore failure. */
    fun reset()
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key (no user authentication). The sealed form is a 12-byte IV
 * followed by the ciphertext and tag. Keystore keys are never backed up, so their loss is a normal path.
 */
internal class KeystoreSecretBox(private val alias: String = DEFAULT_ALIAS) : SecretBox {

    override fun seal(plain: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true) ?: return null)
            val iv = cipher.iv ?: return null
            if (iv.size != IV_LENGTH) return null
            val body = cipher.doFinal(plain)
            val sealed = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, sealed, 0, iv.size)
            System.arraycopy(body, 0, sealed, iv.size, body.size)
            sealed
        } catch (e: Exception) {
            null
        }
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_LENGTH) return null
        return try {
            val key = key(create = false) ?: return null
            val iv = ByteArray(IV_LENGTH)
            System.arraycopy(sealed, 0, iv, 0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
        } catch (e: Exception) {
            null
        }
    }

    override fun reset() {
        try {
            val store = KeyStore.getInstance(KEYSTORE)
            store.load(null)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        } catch (ignored: Exception) {
            // A key that cannot be deleted is replaced by the next failed open/seal cycle.
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE)
        store.load(null)
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null || !create) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "sallapush_credentials_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}

/** The outcome of loading the credentials file. */
internal sealed class CredentialsRead {
    object None : CredentialsRead()

    class Found(val credentials: Credentials) : CredentialsRead()

    /** The file could not be opened or decrypted: key and file were deleted; register again with previous_device_id. */
    object Reset : CredentialsRead()

    class Unavailable(val reason: String) : CredentialsRead()
}

/** credentials.bin: sealed JSON {"v":1,"app_id","api_host","device_id","device_secret"}. */
internal class CredentialStore(
    private val files: SdkFiles,
    private val box: SecretBox,
    private val file: File,
    private val appId: String,
    private val apiHost: String,
) {

    fun load(): CredentialsRead {
        val bytes = when (val read = files.read(file)) {
            is FileRead.Missing -> return CredentialsRead.None
            is FileRead.Unavailable -> return CredentialsRead.Unavailable(read.reason)
            is FileRead.Data -> read.bytes
        }
        val json = box.open(bytes)?.let { parseJsonObject(it.decodeToString()) }
        val deviceId = json?.stringOrNull("device_id")
        val secret = json?.stringOrNull("device_secret")
        val valid = json != null &&
            json.optInt("v", 0) == 1 &&
            json.stringOrNull("app_id") == appId &&
            json.stringOrNull("api_host") == apiHost &&
            deviceId != null && Validation.isUuid(deviceId) &&
            secret != null && Validation.isDeviceSecret(secret)
        if (!valid || deviceId == null || secret == null) {
            reset()
            return CredentialsRead.Reset
        }
        return CredentialsRead.Found(Credentials(deviceId, SecretString(secret)))
    }

    /** False when the keystore could not seal or the file could not be written; the credentials then live in memory only. */
    fun save(credentials: Credentials): Boolean {
        val json = JSONObject()
        return try {
            json.put("v", 1)
            json.put("app_id", appId)
            json.put("api_host", apiHost)
            json.put("device_id", credentials.deviceId)
            json.put("device_secret", credentials.secret.reveal())
            val sealed = box.seal(json.toString().encodeToByteArray()) ?: run {
                box.reset()
                return false
            }
            files.write(file, sealed)
        } catch (e: Exception) {
            false
        }
    }

    fun delete() {
        files.delete(file)
    }

    private fun reset() {
        box.reset()
        files.delete(file)
    }
}

internal object Validation {

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SECRET = Regex("^[A-Za-z0-9_-]+={0,2}$")
    private val APP_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isUuid(value: String): Boolean = UUID.matches(value)

    fun isDeviceSecret(value: String): Boolean = value.length <= MAX_SECRET_LENGTH && SECRET.matches(value)

    /**
     * 1-64 of A-Z, a-z, 0-9, _ and -: the id push-service's admin gives an app, or the UUID push-service creates when
     * it gives none. It names a folder and a URL path segment, so nothing else is accepted.
     */
    fun isAppId(value: String): Boolean = APP_ID.matches(value)

    private const val MAX_SECRET_LENGTH = 512
}
