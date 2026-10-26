package com.salla.push.internal.util

/**
 * How an app-supplied callback (listener or observer) failed, for a log line: the exception's class and
 * the frames it was thrown from as class.method:line. Never its message, which may carry the app's data.
 */
internal object AppCallbacks {

    private const val MAX_FRAMES = 3

    /** "NullPointerException at com.example.MainActivity.onClick:42 < com.example.Router.open:17". */
    fun failure(failure: Throwable): String {
        val frames = try {
            failure.stackTrace.take(MAX_FRAMES).map { frameText(it) }
        } catch (t: Throwable) {
            emptyList()
        }
        return withFrames(failure, frames)
    }
}

/**
 * How a failure inside SallaPush happened, for an internal-error log line that support can act on: the exception's
 * class, where it was thrown when that is outside SallaPush, and the SallaPush frames it went through, as
 * class.method:line. Never its message, which may echo data.
 */
internal object InternalFailure {

    private const val SDK_PACKAGE = "com.salla.push."
    private const val MAX_SDK_FRAMES = 3

    /** "IndexOutOfBoundsException at java.util.ArrayList.get:437 < com.salla.push.internal.engine.TagsKt.sendTags:170". */
    fun describe(failure: Throwable): String {
        val frames = try {
            frames(failure.stackTrace)
        } catch (t: Throwable) {
            emptyList()
        }
        return withFrames(failure, frames)
    }

    private fun frames(trace: Array<StackTraceElement>): List<String> {
        val top = trace.firstOrNull() ?: return emptyList()
        val result = ArrayList<String>(MAX_SDK_FRAMES + 1)
        if (!top.className.startsWith(SDK_PACKAGE)) result.add(frameText(top))
        var sdkFrames = 0
        for (element in trace) {
            if (sdkFrames >= MAX_SDK_FRAMES) break
            if (!element.className.startsWith(SDK_PACKAGE)) continue
            result.add(frameText(element))
            sdkFrames += 1
        }
        return result
    }
}

private fun withFrames(failure: Throwable, frames: List<String>): String {
    val name = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name }
    return if (frames.isEmpty()) name else "$name at ${frames.joinToString(" < ")}"
}

private fun frameText(element: StackTraceElement): String {
    val line = if (element.lineNumber > 0) ":${element.lineNumber}" else ""
    return "${element.className}.${element.methodName}$line"
}
