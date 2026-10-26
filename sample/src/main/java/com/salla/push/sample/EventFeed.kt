package com.salla.push.sample

import android.content.Context
import com.salla.push.SallaPushNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The sample's event feed, newest first: SallaPush's log lines from INFO up and its notif.* DEBUG lines, taps and
 * foreground notifications. It lives as long as the app, so a tap that arrives before MainActivity shows is kept. Main
 * thread only.
 */
object EventFeed {

    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var onChange: (() -> Unit)? = null

    /** Every line, newest first, one per text line. */
    val text: String
        get() = lines.joinToString("\n")

    /** Adds [line], with the time, and tells the observer. */
    fun add(line: String) {
        lines.addFirst(time.format(Date()) + "  " + line)
        while (lines.size > MAX_LINES) lines.removeLast()
        onChange?.invoke()
    }

    /** [observer] runs after every change, until another one is set or it is removed. */
    fun observe(observer: () -> Unit) {
        onChange = observer
    }

    /** Removes [observer] when it is the current one; a newer screen's observer stays. */
    fun stopObserving(observer: () -> Unit) {
        if (onChange === observer) onChange = null
    }

    /** The notification's title, else its body, for one feed line. */
    fun describe(context: Context, notification: SallaPushNotification): String =
        notification.title ?: notification.body ?: context.getString(R.string.no_text)
}
