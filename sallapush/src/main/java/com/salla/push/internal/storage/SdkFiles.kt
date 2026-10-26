package com.salla.push.internal.storage

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** The outcome of reading one file. Unavailable (I/O failure, locked storage) is never treated as "absent". */
internal sealed class FileRead {
    object Missing : FileRead()

    class Data(val bytes: ByteArray) : FileRead()

    class Unavailable(val reason: String) : FileRead()
}

/** The outcome of loading one state file. A corrupt file has already been moved aside when [Corrupt] is returned. */
internal sealed class Loaded<out T> {
    class Ok<T>(val value: T) : Loaded<T>()

    object Missing : Loaded<Nothing>()

    object Corrupt : Loaded<Nothing>()

    class Unavailable(val reason: String) : Loaded<Nothing>()
}

/** Writes an explicit JSON null for a null value (JSONObject.put removes the key otherwise). */
internal fun org.json.JSONObject.putNullable(key: String, value: Any?): org.json.JSONObject =
    put(key, value ?: org.json.JSONObject.NULL)

/**
 * The SDK's files, all under noBackupFilesDir/sallapush (never backed up or device-transferred, whatever the host's
 * backup rules). Only ever used from the engine thread: resolving the directory creates it. [onAccess], when set, is
 * told about every file-system access (an embedder or a test can check which thread does I/O).
 */
internal class SdkFiles(
    private val onAccess: (() -> Unit)? = null,
    private val rootProvider: () -> File?,
) {

    @Volatile
    private var resolvedRoot: File? = null

    /** noBackupFilesDir/sallapush, created if needed; null when storage is unavailable. */
    fun root(): File? {
        resolvedRoot?.let { return it }
        onAccess?.invoke()
        val root = try {
            rootProvider()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            if (!root.isDirectory) root.mkdirs()
        } catch (e: SecurityException) {
            return null
        }
        resolvedRoot = root
        return root
    }

    /** noBackupFilesDir/sallapush/<appId>/<hostTag>. */
    fun appDirectory(appId: String, hostTag: String): File? {
        val root = root() ?: return null
        return File(File(root, appId), hostTag)
    }

    fun read(file: File): FileRead {
        onAccess?.invoke()
        return try {
            FileRead.Data(AtomicFile(file).readFully())
        } catch (e: FileNotFoundException) {
            if (file.exists()) FileRead.Unavailable("not readable") else FileRead.Missing
        } catch (e: Exception) {
            FileRead.Unavailable(e.javaClass.simpleName)
        }
    }

    /** Writes [bytes] atomically (write, sync, rename); false on any failure. */
    fun write(file: File, bytes: ByteArray): Boolean {
        onAccess?.invoke()
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
            true
        } catch (e: Exception) {
            if (stream != null) {
                try {
                    atomic.failWrite(stream)
                } catch (ignored: Exception) {
                    // The original file is kept by AtomicFile.
                }
            }
            false
        }
    }

    fun delete(file: File) {
        onAccess?.invoke()
        try {
            AtomicFile(file).delete()
        } catch (ignored: Exception) {
            // A file that cannot be deleted is overwritten or ignored later.
        }
    }

    /** Whether [file] exists; an unreadable answer counts as existing. */
    fun exists(file: File): Boolean {
        onAccess?.invoke()
        return try {
            file.exists()
        } catch (e: SecurityException) {
            true
        }
    }

    /** Moves a corrupt file aside (for example state.json → state.corrupt.json) so a fresh one can be written. */
    fun moveAside(file: File, newName: String) {
        onAccess?.invoke()
        try {
            val target = File(file.parentFile, newName)
            if (target.exists()) target.delete()
            if (!file.renameTo(target)) file.delete()
        } catch (ignored: Exception) {
            // The corrupt file is overwritten by the next successful write.
        }
    }

    /** The direct subdirectories of [directory]; empty on failure. */
    fun subdirectories(directory: File): List<File> {
        onAccess?.invoke()
        return try {
            directory.listFiles()?.filter { it.isDirectory } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
