package com.salla.push.internal.engine

import com.salla.push.internal.log.LogCode

// Flush triggers of the op log: foreground, resume, background and connectivity. There is no
// polling and no periodic work.

/** The app came to the foreground (first started activity). */
internal fun Engine.onForeground() {
    backgroundLaunch = false
    applyTrigger()
    refreshPermissionNow()
    ensureRegisterOp()
    ensureUpdateOp()
    requestEventsFlush()
    armTokenMissingCheck()
}

/** An activity resumed (debounced): a host's own permission prompt is seen here, and the heartbeat is checked. */
internal fun Engine.onActivityResumed() {
    refreshPermissionNow()
    ensureUpdateOp()
    requestEventsFlush()
}

/** The app went to the background: tags and events flush at once, and pending work is handed to WorkManager. */
internal fun Engine.onBackground() {
    tokenMissingTimer?.cancel()
    tokenMissingTimer = null
    flushTagsNow()
    requestEventsFlush()
    scheduleDurableWork()
}

/** A network became available after a network failure. */
internal fun Engine.onConnectivityRegained() {
    applyTrigger()
}

/** The device's time zone or locale changed: an update carries the new values. */
internal fun Engine.onDeviceSettingsChanged() {
    ensureUpdateOp()
}

/** WARN token.missing when the app has been in the foreground for 30 s without a push token (once per engine). */
private fun Engine.armTokenMissingCheck() {
    if (device.push.token != null || tokenMissingTimer != null || tokenMissingLogged) return
    val generationNow = generation
    tokenMissingTimer = env.runner.schedule(TOKEN_MISSING_MILLIS, Runnable {
        if (generationNow == generation) {
            task {
                tokenMissingTimer = null
                if (device.push.token == null && host.isForeground() && !tokenMissingLogged) {
                    tokenMissingLogged = true
                    logger.log(LogCode.TOKEN_MISSING) { "No push token 30 s after the app came to the foreground. ${host.tokenMissingAdvice()}" }
                }
            }
        }
    })
}

private const val TOKEN_MISSING_MILLIS = 30_000L
