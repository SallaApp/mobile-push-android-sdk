package com.salla.push.sample

import android.content.Context
import com.salla.push.SallaPushClickEvent

/**
 * The sample's stand-in for your app's navigation, called by SampleApp's click listener for every tap. A real app opens
 * the screen the tap is about, from the notification's data, and starts it from the Application with
 * FLAG_ACTIVITY_NEW_TASK (docs/REFERENCE.md, "Opening a screen from the listener"); the sample adds the tap to its
 * event feed, which MainActivity shows.
 */
object SampleRouter {

    /** Records [event] in the event feed. Main thread: SallaPush calls click listeners there. */
    fun open(context: Context, event: SallaPushClickEvent) {
        val notification = event.notification
        val url = event.result.url ?: context.getString(R.string.none)
        EventFeed.add(context.getString(R.string.event_tap, EventFeed.describe(context, notification), notification.additionalData, url))
    }
}
