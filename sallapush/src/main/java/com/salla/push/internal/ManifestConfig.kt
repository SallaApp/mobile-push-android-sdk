package com.salla.push.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.salla.push.SallaPushLogLevel
import com.salla.push.internal.util.asciiLowercase

/**
 * The app's manifest meta-data the SDK honours. Read once, off the caller thread (it is a PackageManager call):
 * com.salla.push.LOG_LEVEL, com.salla.push.default_notification_icon, com.salla.push.default_channel_id,
 * com.salla.push.default_channel_name, and Firebase's default icon, colour and channel keys.
 */
internal class ManifestConfig(
    val logLevel: SallaPushLogLevel?,
    val smallIcon: Int,
    val firebaseSmallIcon: Int,
    val colorResource: Int,
    val defaultChannelId: String?,
    val defaultChannelName: String?,
    val firebaseChannelId: String?,
    /** com.salla.push.LOG_LEVEL as written, when it is present but not a log level (it is then ignored). */
    val invalidLogLevel: String? = null,
) {

    companion object {

        const val KEY_LOG_LEVEL = "com.salla.push.LOG_LEVEL"
        const val KEY_SMALL_ICON = "com.salla.push.default_notification_icon"
        const val KEY_CHANNEL_ID = "com.salla.push.default_channel_id"
        const val KEY_CHANNEL_NAME = "com.salla.push.default_channel_name"
        const val KEY_FIREBASE_SMALL_ICON = "com.google.firebase.messaging.default_notification_icon"
        const val KEY_FIREBASE_COLOR = "com.google.firebase.messaging.default_notification_color"
        const val KEY_FIREBASE_CHANNEL_ID = "com.google.firebase.messaging.default_notification_channel_id"

        val EMPTY = ManifestConfig(null, 0, 0, 0, null, null, null)

        fun read(context: Context): ManifestConfig {
            val metaData = try {
                val manager = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                }
                info.metaData
            } catch (e: Exception) {
                null
            } ?: return EMPTY
            val levelText = levelText(context, metaData)
            val level = parseLogLevel(levelText)
            return ManifestConfig(
                logLevel = level,
                smallIcon = resource(metaData, KEY_SMALL_ICON),
                firebaseSmallIcon = resource(metaData, KEY_FIREBASE_SMALL_ICON),
                colorResource = resource(metaData, KEY_FIREBASE_COLOR),
                defaultChannelId = string(context, metaData, KEY_CHANNEL_ID),
                defaultChannelName = string(context, metaData, KEY_CHANNEL_NAME),
                firebaseChannelId = string(context, metaData, KEY_FIREBASE_CHANNEL_ID),
                invalidLogLevel = if (levelText != null && level == null) levelText else null,
            )
        }

        /**
         * com.salla.push.LOG_LEVEL as text: a string, a string resource, or any other value as written (android:value="5"
         * arrives as a number). Null when absent or blank.
         */
        @Suppress("DEPRECATION")
        private fun levelText(context: Context, metaData: Bundle): String? {
            val value = try {
                metaData.get(KEY_LOG_LEVEL)
            } catch (e: Exception) {
                null
            }
            val text = when (value) {
                null -> null
                is String -> value
                is Int -> try {
                    context.getString(value)
                } catch (e: Exception) {
                    value.toString()
                }
                else -> value.toString()
            }
            return text?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** A level name as iOS reads SallaPushLogLevel: off, error, warn (or warning), info, debug, verbose. */
        fun parseLogLevel(value: String?): SallaPushLogLevel? {
            val name = value?.trim()?.let { asciiLowercase(it) } ?: return null
            return when (name) {
                "off" -> SallaPushLogLevel.OFF
                "error" -> SallaPushLogLevel.ERROR
                "warn", "warning" -> SallaPushLogLevel.WARN
                "info" -> SallaPushLogLevel.INFO
                "debug" -> SallaPushLogLevel.DEBUG
                "verbose" -> SallaPushLogLevel.VERBOSE
                else -> null
            }
        }

        /** A string value, or a string resource referenced with android:resource. */
        @Suppress("DEPRECATION")
        private fun string(context: Context, metaData: Bundle, key: String): String? {
            val value = try {
                metaData.get(key)
            } catch (e: Exception) {
                null
            }
            val text = when (value) {
                is String -> value
                is Int -> try {
                    context.getString(value)
                } catch (e: Exception) {
                    null
                }
                else -> null
            }
            return text?.trim()?.takeIf { it.isNotEmpty() }
        }

        @Suppress("DEPRECATION")
        private fun resource(metaData: Bundle, key: String): Int {
            return try {
                metaData.get(key) as? Int ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }
}
