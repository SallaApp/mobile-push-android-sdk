package com.salla.push

import android.content.Context
import com.salla.push.internal.DebugFacade
import com.salla.push.internal.MigrationFacade
import com.salla.push.internal.NotificationsFacade
import com.salla.push.internal.Sdk
import com.salla.push.internal.UserFacade
import com.salla.push.internal.util.EntryGuard
import com.salla.push.internal.util.entry
import com.salla.push.internal.util.entryValue
import com.salla.push.internal.util.isNull

/**
 * SallaPush: push registration, server-verified customer identity, tags, events and notification display for the
 * Android apps of Salla stores. Every method is safe from any thread, before or after [initWithContext]; nothing blocks
 * on I/O, nothing throws, and bad input is logged and ignored.
 *
 * Observers and listeners are held strongly until they are removed: remove one an Activity or Fragment added when it
 * is destroyed.
 */
public object SallaPush {

    /** Starts SallaPush for [appId] (for example "00000000-0000-4000-8000-000000000002"). Call in Application.onCreate; safe in every process. */
    @JvmStatic
    public fun initWithContext(context: Context, appId: String): Unit = entry("initWithContext") {
        if (isNull(context)) return@entry EntryGuard.nullArgument("initWithContext", "context")
        if (isNull(appId)) return@entry EntryGuard.nullArgument("initWithContext", "appId")
        Sdk.core.initialize(context, appId, null)
    }

    /** Starts SallaPush for [appId] with [config]. */
    @JvmStatic
    public fun initWithContext(context: Context, appId: String, config: SallaPushConfig): Unit = entry("initWithContext") {
        if (isNull(context)) return@entry EntryGuard.nullArgument("initWithContext", "context")
        if (isNull(appId)) return@entry EntryGuard.nullArgument("initWithContext", "appId")
        // A Java null config means the defaults: starting without push would cost more than the log line.
        val chosen: SallaPushConfig? = if (isNull(config)) null else config
        if (chosen == null) EntryGuard.nullArgument("initWithContext", "config", "the defaults are used")
        Sdk.core.initialize(context, appId, chosen)
    }

    /**
     * True after a successful [initWithContext] in this process. False in a process other than the app's main one,
     * where SallaPush only shows notifications, once it has checked (milliseconds after [initWithContext]).
     */
    @JvmStatic
    public val isInitialized: Boolean
        get() = entryValue("isInitialized", false) { Sdk.core.isInitialized }

    /** This SDK's version, for example "1.1.0". */
    @JvmStatic
    public val sdkVersion: String
        get() = BuildConfig.SDK_VERSION

    /** The app id passed to [initWithContext]; null before. */
    @JvmStatic
    public val appId: String?
        get() = entryValue("appId", null) { Sdk.core.appId }

    /**
     * Requires [setConsentGiven] before SallaPush sends or stores anything (PDPL). While consent is required and not
     * given, nothing is sent and nothing is written but the consent answer (consent.json), except the one minimised
     * state a withdrawal saves before its requests; the getters keep showing the state they showed, and calls are kept
     * until consent is given.
     */
    @JvmStatic
    public fun setConsentRequired(required: Boolean): Unit = entry("setConsentRequired") {
        Sdk.core.setConsentRequired(required)
    }

    /**
     * Records the user's consent (persisted for the app). While consent is required, withdrawing it (false after it was
     * given) saves the minimised state once, without the customer's external id, then opts the device out, clears its
     * tags when it may have some and unlinks it when it may be linked, retried in this process only; SallaPush then
     * stays idle, writing nothing but the consent answer, until consent is given again. The getters then keep the device
     * id, the token and the login state, and show no external id and no tags (user observers hear the external id go).
     * While consent is not required, false is only saved.
     */
    @JvmStatic
    public fun setConsentGiven(given: Boolean): Unit = entry("setConsentGiven") {
        Sdk.core.setConsentGiven(given)
    }

    /**
     * Links this device to the customer [externalId] (the Salla customer id, 1-128 characters), proven by
     * [identityToken] (the customer's current Salla token, whose subject is [externalId]). Call at sign-in and at every
     * launch while signed in; call [logout] after the customer signs out.
     * The token is kept in memory only, never stored or logged; a token the SallaPush server rejects is reported to
     * user observers ([SallaPushLoginFailure.TOKEN_REJECTED]). An empty or longer id, or an empty token or one longer
     * than 4,096 characters, is ignored (WARN api.invalid_argument).
     */
    @JvmStatic
    public fun login(externalId: String, identityToken: String): Unit = entry("login") {
        if (isNull(externalId)) return@entry EntryGuard.nullArgument("login", "externalId")
        if (isNull(identityToken)) return@entry EntryGuard.nullArgument("login", "identityToken")
        Sdk.core.login(externalId, identityToken)
    }

    /**
     * Unlinks the customer from this device (it stays registered as a guest). Needs no token, and works offline: it is
     * kept and retried until the server confirms it. With clearTagsOnLogout (the default) it also clears this device's
     * tags; at a first launch or after a reinstall, the tags set before it in that launch are kept.
     */
    @JvmStatic
    public fun logout(): Unit = entry("logout") {
        Sdk.core.logout()
    }

    /** The current user: tags, language, login state and the push subscription. Java: SallaPush.getUser(). */
    @JvmStatic
    public val User: SallaPushUser
        get() = UserFacade

    /** Notification permission, FCM forwarding, listeners and clearing. Java: SallaPush.getNotifications(). */
    @JvmStatic
    public val Notifications: SallaPushNotifications
        get() = NotificationsFacade

    /** Logging controls. Java: SallaPush.getDebug(). */
    @JvmStatic
    public val Debug: SallaPushDebug
        get() = DebugFacade

    /** Helpers for apps that also run another push SDK. Java: SallaPush.getMigration(). */
    @JvmStatic
    public val Migration: SallaPushMigration
        get() = MigrationFacade
}
