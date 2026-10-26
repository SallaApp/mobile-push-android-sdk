package com.salla.push.internal.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.salla.push.BuildConfig
import com.salla.push.internal.util.cleanField
import java.util.Locale
import java.util.TimeZone

/** The device metadata sent with registration and updates, already trimmed and truncated (UTF-16 units). */
internal class DeviceFacts(
    val appVersion: String?,
    val sdkVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
)

/** Reads device metadata; called on the engine thread only. */
internal interface DeviceInfoSource {
    fun facts(): DeviceFacts

    /** The language part of the device locale ("ar-SA" → "ar"). */
    fun deviceLanguage(): String?

    fun timezone(): String?
}

internal class AndroidDeviceInfo(
    private val context: Context,
    private val appVersionOverride: () -> String?,
) : DeviceInfoSource {

    @Volatile
    private var packageVersion: String? = null

    @Volatile
    private var packageVersionRead = false

    override fun facts(): DeviceFacts = DeviceFacts(
        appVersion = cleanField(appVersionOverride() ?: packageVersionName(), MAX_VERSION),
        sdkVersion = cleanField(BuildConfig.SDK_VERSION, MAX_VERSION),
        osVersion = cleanField(Build.VERSION.RELEASE, MAX_VERSION),
        deviceModel = cleanField(Build.MODEL, MAX_MODEL),
    )

    override fun deviceLanguage(): String? = languageOf(Locale.getDefault())

    override fun timezone(): String? = cleanField(TimeZone.getDefault().id, MAX_TIMEZONE)

    private fun packageVersionName(): String? {
        if (packageVersionRead) return packageVersion
        val name = try {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        } catch (e: Exception) {
            null
        }
        packageVersion = name
        packageVersionRead = true
        return name
    }

    companion object {
        const val MAX_VERSION = 32
        const val MAX_MODEL = 64
        const val MAX_LANGUAGE = 16
        const val MAX_TIMEZONE = 64

        /** The language subtag of a locale or BCP 47 tag, normalised by the platform ("iw" becomes "he"). */
        fun languageOf(locale: Locale?): String? {
            val tag = locale?.toLanguageTag() ?: return null
            val language = tag.substringBefore('-')
            if (language.isEmpty() || language == "und") return null
            return cleanField(language, MAX_LANGUAGE)
        }
    }
}
