package com.salla.push.consumercheck.readme

import com.salla.push.SallaPush
import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLogListener

/** The logging snippets of README.md and docs/LOGGING.md; the app supplies its error reporting. */
abstract class LoggingSnippets {

    abstract val telemetry: Telemetry

    fun logEverything() {
        // snippet:runtime-log-level
        SallaPush.Debug.logLevel = SallaPushLogLevel.VERBOSE
        // end-snippet
    }

    fun reportErrors() {
        // snippet:log-listener
        val telemetryListener = SallaPushLogListener { event ->
            telemetry.report("sallapush", event.code, event.message, event.metadata)
        }
        SallaPush.Debug.addLogListener(telemetryListener, SallaPushLogLevel.ERROR)
        // end-snippet
    }
}
