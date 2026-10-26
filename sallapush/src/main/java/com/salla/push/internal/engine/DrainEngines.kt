package com.salla.push.internal.engine

import com.salla.push.internal.api.ApiBaseUrl
import com.salla.push.internal.storage.FileRead
import com.salla.push.internal.storage.HostTag
import com.salla.push.internal.storage.IdentityCodec
import com.salla.push.internal.storage.IdentityStore
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.internal.storage.Validation
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.internal.util.stringOrNull
import java.io.File

/** A folder of another app id or host that still has a pending logout or unlink. */
internal class DrainCandidate(val key: EngineKey, val apiBaseUrl: String)

/**
 * Finds pending logouts left behind by an app id or host switch: every
 * noBackupFilesDir/sallapush/<appId>/<hostTag>/identity.json other than the current one, with a pending
 * logout/unlink and a credentials file, gets a drain-only engine. A folder's credentials only ever go to the API
 * host its own identity.json names, and only when that host still hashes to the folder's host tag.
 */
internal object DrainScanner {

    const val MAX_PER_LAUNCH = 3

    fun scan(files: SdkFiles, exclude: Set<EngineKey>): List<DrainCandidate> {
        val root = files.root() ?: return emptyList()
        val result = ArrayList<DrainCandidate>()
        for (appDirectory in files.subdirectories(root)) {
            val appId = appDirectory.name
            if (!Validation.isAppId(appId)) continue
            for (hostDirectory in files.subdirectories(appDirectory)) {
                if (result.size >= MAX_PER_LAUNCH) return result
                val key = EngineKey(appId, hostDirectory.name)
                if (key in exclude) continue
                candidate(files, key, hostDirectory)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun candidate(files: SdkFiles, key: EngineKey, directory: File): DrainCandidate? {
        if (!File(directory, CREDENTIALS_FILE).isFile) return null
        val read = files.read(File(directory, IdentityStore.FILE_NAME)) as? FileRead.Data ?: return null
        val identity = IdentityCodec.decode(read.bytes) ?: return null
        if (identity.ops.none { it.kind.isLogoutLike }) return null
        val json = parseJsonObject(read.bytes.decodeToString()) ?: return null
        if (json.stringOrNull("app_id") != key.appId) return null
        val baseUrl = ApiBaseUrl.normalize(json.stringOrNull("api_base_url")) ?: return null
        if (HostTag.of(baseUrl) != key.hostTag) return null
        return DrainCandidate(key, baseUrl)
    }

    private const val CREDENTIALS_FILE = "credentials.bin"
}
