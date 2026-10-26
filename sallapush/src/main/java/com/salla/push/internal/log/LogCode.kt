package com.salla.push.internal.log

import com.salla.push.SallaPushLogLevel
import com.salla.push.SallaPushLogLevel.DEBUG
import com.salla.push.SallaPushLogLevel.ERROR
import com.salla.push.SallaPushLogLevel.INFO
import com.salla.push.SallaPushLogLevel.VERBOSE
import com.salla.push.SallaPushLogLevel.WARN

internal object LogCategory {
    const val LIFECYCLE = "lifecycle"
    const val REGISTRATION = "registration"
    const val IDENTITY = "identity"
    const val NOTIFICATIONS = "notifications"
    const val EVENTS = "events"
    const val TAGS = "tags"
    const val NETWORK = "network"
    const val STORAGE = "storage"
    const val CONSENT = "consent"
}

/**
 * Every log code the Android SDK emits, with the level and category it is always emitted at. The codes are stable
 * API shared with the iOS SDK (the log catalogue); an emitted code is never outside this list.
 */
internal enum class LogCode(val code: String, val level: SallaPushLogLevel, val category: String) {
    INIT_STARTED("init.started", INFO, LogCategory.LIFECYCLE),
    INIT_INVALID_APP_ID("init.invalid_app_id", ERROR, LogCategory.LIFECYCLE),
    INIT_APP_ID_CHANGED("init.app_id_changed", WARN, LogCategory.LIFECYCLE),
    INIT_DRAIN_STARTED("init.drain_started", INFO, LogCategory.LIFECYCLE),
    INIT_BUFFER_FULL("init.buffer_full", WARN, LogCategory.LIFECYCLE),
    INIT_NOT_MAIN_PROCESS("init.not_main_process", DEBUG, LogCategory.LIFECYCLE),
    CONSENT_WAITING("consent.waiting", INFO, LogCategory.CONSENT),
    CONSENT_GIVEN("consent.given", INFO, LogCategory.CONSENT),
    CONSENT_REVOKED("consent.revoked", INFO, LogCategory.CONSENT),
    API_INVALID_ARGUMENT("api.invalid_argument", WARN, LogCategory.LIFECYCLE),
    API_NULL_ARGUMENT("api.null_argument", ERROR, LogCategory.LIFECYCLE),
    API_CALLBACK_THREW("api.callback_threw", ERROR, LogCategory.LIFECYCLE),
    TOKEN_RECEIVED("token.received", INFO, LogCategory.REGISTRATION),
    TOKEN_UNCHANGED("token.unchanged", DEBUG, LogCategory.REGISTRATION),
    TOKEN_INVALID("token.invalid", WARN, LogCategory.REGISTRATION),
    TOKEN_MISSING("token.missing", WARN, LogCategory.REGISTRATION),
    TOKEN_FIREBASE_MISSING("token.firebase_missing", WARN, LogCategory.REGISTRATION),
    TOKEN_AUTO_INIT_DISABLED("token.auto_init_disabled", DEBUG, LogCategory.REGISTRATION),
    TOKEN_FETCH_FAILED("token.fetch_failed", WARN, LogCategory.REGISTRATION),
    REGISTER_OK("register.ok", INFO, LogCategory.REGISTRATION),
    REGISTER_REREGISTERING("register.reregistering", INFO, LogCategory.REGISTRATION),
    REGISTER_DORMANT("register.dormant", ERROR, LogCategory.REGISTRATION),
    UPDATE_SENT("update.sent", DEBUG, LogCategory.REGISTRATION),
    HEARTBEAT_SENT("heartbeat.sent", DEBUG, LogCategory.REGISTRATION),
    PERMISSION_CHANGED("permission.changed", INFO, LogCategory.REGISTRATION),
    PERMISSION_PROMPT_DEFERRED("permission.prompt_deferred", INFO, LogCategory.REGISTRATION),
    SUBSCRIPTION_OPTED_OUT("subscription.opted_out", INFO, LogCategory.REGISTRATION),
    SUBSCRIPTION_OPTED_IN("subscription.opted_in", INFO, LogCategory.REGISTRATION),
    LOGIN_QUEUED("login.queued", DEBUG, LogCategory.IDENTITY),
    LOGIN_OK("login.ok", INFO, LogCategory.IDENTITY),
    LOGIN_SKIPPED_SAME("login.skipped_same", DEBUG, LogCategory.IDENTITY),
    LOGIN_SWITCHED("login.switched", INFO, LogCategory.IDENTITY),
    LOGIN_REJECTED("login.rejected", WARN, LogCategory.IDENTITY),
    LOGOUT_QUEUED("logout.queued", INFO, LogCategory.IDENTITY),
    LOGOUT_OK("logout.ok", INFO, LogCategory.IDENTITY),
    LOGOUT_SKIPPED("logout.skipped", DEBUG, LogCategory.IDENTITY),
    IDENTITY_TOKEN_NEEDED("identity.token_needed", WARN, LogCategory.IDENTITY),
    IDENTITY_CLIENT_ERROR("identity.client_error", ERROR, LogCategory.IDENTITY),
    TAGS_FLUSHED("tags.flushed", DEBUG, LogCategory.TAGS),
    TAGS_NORMALIZED("tags.normalized", DEBUG, LogCategory.TAGS),
    TAGS_INVALID_KEY("tags.invalid_key", WARN, LogCategory.TAGS),
    TAGS_VALUE_TRUNCATED("tags.value_truncated", WARN, LogCategory.TAGS),
    TAGS_LIMIT("tags.limit", WARN, LogCategory.TAGS),
    TAGS_REJECTED_KEY("tags.rejected_key", WARN, LogCategory.TAGS),
    EVENTS_FLUSHED("events.flushed", DEBUG, LogCategory.EVENTS),
    EVENTS_DROPPED("events.dropped", WARN, LogCategory.EVENTS),
    EVENTS_QUEUE_FULL("events.queue_full", WARN, LogCategory.EVENTS),
    NET_RETRY("net.retry", INFO, LogCategory.NETWORK),
    NET_RETRY_EXHAUSTED("net.retry_exhausted", WARN, LogCategory.NETWORK),
    NET_RATE_LIMITED("net.rate_limited", WARN, LogCategory.NETWORK),
    NET_CLIENT_ERROR("net.client_error", ERROR, LogCategory.NETWORK),
    NET_REDIRECT_REFUSED("net.redirect_refused", ERROR, LogCategory.NETWORK),
    NET_INVALID_RESPONSE("net.invalid_response", WARN, LogCategory.NETWORK),
    NET_ROUTES_CLOSED("net.routes_closed", WARN, LogCategory.NETWORK),
    NET_MONITOR_UNAVAILABLE("net.monitor_unavailable", WARN, LogCategory.NETWORK),
    STORAGE_FAILED("storage.failed", ERROR, LogCategory.STORAGE),
    STORAGE_STATE_RESET("storage.state_reset", ERROR, LogCategory.STORAGE),
    STORAGE_CRYPTO_RESET("storage.crypto_reset", WARN, LogCategory.STORAGE),
    NOTIF_FOREIGN("notif.foreign", VERBOSE, LogCategory.NOTIFICATIONS),
    NOTIF_PARSE_FAILED("notif.parse_failed", WARN, LogCategory.NOTIFICATIONS),
    NOTIF_UNKNOWN_VERSION("notif.unknown_version", WARN, LogCategory.NOTIFICATIONS),
    NOTIF_SUPPRESSED("notif.suppressed", DEBUG, LogCategory.NOTIFICATIONS),
    NOTIF_DISPLAY_FAILED("notif.display_failed", WARN, LogCategory.NOTIFICATIONS),
    NOTIF_IMAGE_UNUSABLE("notif.image_unusable", WARN, LogCategory.NOTIFICATIONS),
    NOTIF_CLICK_QUEUED("notif.click_queued", DEBUG, LogCategory.NOTIFICATIONS),
    NOTIF_CLICK_DELIVERED("notif.click_delivered", DEBUG, LogCategory.NOTIFICATIONS),
    DISPLAY_DEFAULT_ICON("display.default_icon", WARN, LogCategory.NOTIFICATIONS),
    BADGE_CLEARED("badge.cleared", DEBUG, LogCategory.NOTIFICATIONS),
    BADGE_CHANNEL_BADGES_ON("badge.channel_badges_on", WARN, LogCategory.NOTIFICATIONS),
    FCM_FORWARDING_HINT("fcm.forwarding_hint", INFO, LogCategory.NOTIFICATIONS),
    WORK_UNAVAILABLE("work.unavailable", WARN, LogCategory.LIFECYCLE),
    LOG_DROPPED("log.dropped", WARN, LogCategory.LIFECYCLE),
    INTERNAL_ERROR("internal.error", ERROR, LogCategory.LIFECYCLE),
}

/** The metadata keys a log event may carry (log catalogue A.1); any other key is dropped. */
internal object Meta {
    const val HTTP_STATUS = "http_status"
    const val ERROR_CODE = "error_code"
    const val REQUEST_ID = "request_id"
    const val DEVICE_ID = "device_id"
    const val APP_ID = "app_id"
    const val ATTEMPT = "attempt"
    const val DELAY_S = "delay_s"
    const val ROUTE = "route"
    const val REASON = "reason"
    const val COUNT = "count"

    val ALLOWED: Set<String> = setOf(HTTP_STATUS, ERROR_CODE, REQUEST_ID, DEVICE_ID, APP_ID, ATTEMPT, DELAY_S, ROUTE, REASON, COUNT)
}
