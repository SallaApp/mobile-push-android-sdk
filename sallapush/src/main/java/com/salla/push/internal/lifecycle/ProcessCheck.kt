package com.salla.push.internal.lifecycle

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** Whether this is the app's main process (the SDK only shows notifications in others, such as a ":pushservice"). */
internal fun interface ProcessInfo {
    fun isMainProcess(): Boolean
}

internal class AndroidProcessInfo(private val context: Context) : ProcessInfo {

    /** Called on the engine thread: below API 28 it reads /proc/self/cmdline. Unknown counts as the main process. */
    override fun isMainProcess(): Boolean {
        val expected = try {
            context.applicationInfo?.processName ?: context.packageName
        } catch (e: Exception) {
            null
        } ?: return true
        val current = currentProcessName() ?: return true
        return current == expected
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                Application.getProcessName()
            } catch (e: Exception) {
                null
            }
        }
        return try {
            val bytes = File("/proc/self/cmdline").readBytes()
            var end = 0
            while (end < bytes.size && bytes[end] != 0.toByte()) end += 1
            if (end == 0) null else bytes.decodeToString(0, end).trim()
        } catch (e: Exception) {
            null
        }
    }
}
