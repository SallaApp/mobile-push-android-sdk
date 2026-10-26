package com.salla.push.internal.engine

/** What the app last asserted: unknown means a fresh identity state (first install, reinstall, corrupt file). */
internal enum class Desired(val wire: String) {
    UNKNOWN("unknown"),
    GUEST("guest"),
    CUSTOMER("customer"),
    ;

    companion object {
        fun from(wire: String?): Desired = values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** What the server last reported about this device's customer link. */
internal enum class ServerLink(val wire: String) {
    UNKNOWN("unknown"),
    NONE("none"),
    LINKED("linked"),
    ;

    companion object {
        fun from(wire: String?): ServerLink = values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** Whether the server holds tags for this device: unknown counts as "may have tags". */
internal enum class ServerTags(val wire: String) {
    UNKNOWN("unknown"),
    NONE("none"),
    SOME("some"),
    ;

    companion object {
        fun from(wire: String?): ServerTags = values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** A login links the device; a logout and its confirmation unlink it. */
internal enum class IdentityOpKind(val wire: String) {
    LOGIN("login"),
    LOGOUT("logout"),
    LOGOUT_CONFIRM("logout_confirm"),
    ;

    val isLogoutLike: Boolean
        get() = this != LOGIN

    companion object {
        /** beta.1's `unlink` (of a link the app did not make) reads as a logout: both are sent as an unlink. */
        fun from(wire: String?): IdentityOpKind? = if (wire == "unlink") LOGOUT else values().firstOrNull { it.wire == wire }
    }
}

internal enum class DeviceOpKind(val wire: String) {
    REGISTER("register"),
    UPDATE("update"),
    TAGS("tags"),
    ;

    companion object {
        fun from(wire: String?): DeviceOpKind? = values().firstOrNull { it.wire == wire }
    }
}

internal enum class FailureKind(val wire: String) {
    NETWORK("network"),
    SERVER("server"),
    RATE_LIMITED("rate_limited"),
    CLIENT("client"),
    ;

    /** Network and 5xx failures are retried at once when a trigger fires; 429 and client waits are never shortened. */
    val resetsOnTrigger: Boolean
        get() = this == NETWORK || this == SERVER

    companion object {
        fun from(wire: String?): FailureKind? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * The registration status of state.json. beta.1's `token_dead` reads as [NONE], which the credentials turn into
 * REGISTERED at load.
 */
internal enum class RegStatus(val wire: String) {
    NONE("none"),
    REGISTERED("registered"),
    DORMANT("dormant"),
    ;

    companion object {
        fun from(wire: String?): RegStatus = values().firstOrNull { it.wire == wire } ?: NONE
    }
}

/**
 * Why registration waits 24 h. beta.1's app_disabled, platform_mismatch and app_mismatch read as null, and such a wait
 * ends at load.
 */
internal enum class DormantReason(val wire: String) {
    UNKNOWN_APP("unknown_app"),
    NOT_FOUND("not_found"),
    INVALID_REQUEST("invalid_request"),
    ;

    /** Device routes closed: every device route waits, identity ops included. The others only block registration. */
    val blocksDeviceRoutes: Boolean
        get() = this == NOT_FOUND

    companion object {
        fun from(wire: String?): DormantReason? = values().firstOrNull { it.wire == wire }
    }
}

/** Retry bookkeeping of one operation; times are epoch seconds. */
internal class Retry(
    var attempts: Int = 0,
    var nextAt: Double = 0.0,
    var lastAttemptAt: Double = 0.0,
    var lastFailure: FailureKind? = null,
    var dormantUntil: Double? = null,
)

/**
 * One identity operation; a login carries the [externalId] it links. [sent] means a request of its link or unlink may
 * have changed server state: it is set when that request goes out and cleared again when the server answered with an
 * error (identity routes are transactional). With [clearTags] (a logout, or a login of another customer, with
 * clearTagsOnLogout) one PATCH removes [tagKeys] from the device before the link or unlink.
 */
internal class IdentityOp(
    val id: Long,
    val kind: IdentityOpKind,
    val externalId: String?,
    var clearTags: Boolean,
    var sent: Boolean,
    val retry: Retry,
    /** The tag keys the clearing removes; empty: the keys SallaPush holds when it is sent. */
    var tagKeys: List<String> = emptyList(),
    /** The clearing is done: the link or the unlink is left. */
    var tagsCleared: Boolean = false,
) {
    /** Whether the op's next request removes the device's tags. */
    val clearsTagsNext: Boolean
        get() = clearTags && !tagsCleared

    /** Takes on the tag clearing [replaced], an unsent op this one replaces, had still to send. */
    fun absorb(replaced: IdentityOp) {
        if (!replaced.clearsTagsNext) return
        clearTags = true
        tagKeys = (tagKeys + replaced.tagKeys).distinct().sorted()
    }
}

/** identity.json: never holds the identity token or the device secret. */
internal class IdentityState {
    var desired: Desired = Desired.UNKNOWN

    /** The customer the app logged in, while it asserts one. */
    var desiredExternalId: String? = null
    var serverLink: ServerLink = ServerLink.UNKNOWN

    /** The customer of the link the server confirmed on this device row; null when it has no link or none is known. */
    var linkedExternalId: String? = null
    var serverTags: ServerTags = ServerTags.UNKNOWN
    val ops: MutableList<IdentityOp> = ArrayList()

    /**
     * A copy to write at once, whose fields can be changed without touching this state. Its collections are copies;
     * the ops in them are this state's own, which a write only reads.
     */
    fun copyForWrite(): IdentityState {
        val copy = IdentityState()
        copy.desired = desired
        copy.desiredExternalId = desiredExternalId
        copy.serverLink = serverLink
        copy.linkedExternalId = linkedExternalId
        copy.serverTags = serverTags
        copy.ops.addAll(ops)
        return copy
    }
}

internal class Registration {
    var status: RegStatus = RegStatus.NONE
    var registeredToken: String? = null

    /** Always null on Android (there is no APNs environment); kept for the shared schema. */
    var registeredEnv: String? = null
    var sentFingerprint: String? = null
    var lastFullUpdateAt: Double = 0.0
    var dormantUntil: Double? = null
    var dormantReason: DormantReason? = null
}

internal class PushPrefs {
    var token: String? = null
    var userOptedOut: Boolean = false

    /** The app language set with setLanguage; null means the device language. */
    var language: String? = null
}

/** A pending tag change, with the identity epoch of the call that made it. */
internal class PendingTag(val value: String, var epoch: Long)

internal class TagsState {
    val mirror: MutableMap<String, String> = LinkedHashMap()
    val pendingAdd: MutableMap<String, PendingTag> = LinkedHashMap()
    val pendingRemove: MutableMap<String, Long> = LinkedHashMap()
}

internal class DeviceOp(val id: Long, val kind: DeviceOpKind, val retry: Retry)

/** A tap kept for a click listener that does not exist yet (Android only; at most 10, for 5 minutes). */
internal class PendingClick(
    val nonce: String,
    val at: Double,
    val androidNotificationId: Int,
    val payload: Map<String, String>,
)

/** state.json: never holds the device secret or the identity token. */
internal class DeviceState {
    var lastDeviceId: String? = null
    val registration: Registration = Registration()
    val push: PushPrefs = PushPrefs()
    val tags: TagsState = TagsState()
    val ops: MutableList<DeviceOp> = ArrayList()
    val pendingClicks: MutableList<PendingClick> = ArrayList()
}

internal class PendingEvent(
    val notificationId: String,
    val type: String,
    val at: String,
    val atSeconds: Double,
    val actionId: String?,
)

/** events.json: pending events (at most 500) and dedupe keys "id|type" (at most 1000, least recently used dropped). */
internal class EventsState {
    val pending: MutableList<PendingEvent> = ArrayList()
    val seen: LinkedHashSet<String> = LinkedHashSet()
}

/** Device credentials: one sealed file, so the id and the secret are never split. */
internal class Credentials(val deviceId: String, val secret: com.salla.push.internal.log.SecretString)
