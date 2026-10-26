package com.salla.push

import com.salla.push.internal.BadgeDefaults
import com.salla.push.internal.util.EntryGuard
import com.salla.push.internal.util.entryValue
import com.salla.push.internal.util.isNull

/** Optional settings for [SallaPush.initWithContext]. Build one with [Builder]. */
public class SallaPushConfig private constructor(builder: Builder) {

    /** Tests only: a server in place of SallaPush's own. Internal and synthetic, so no app code can set or read it. */
    @get:JvmSynthetic
    internal val apiBaseUrl: String? = builder.apiBaseUrl

    /** The Logcat level; null when not set explicitly (then the manifest meta-data or WARN applies). */
    public val logLevel: SallaPushLogLevel? = builder.logLevel

    /** The app version reported to SallaPush; null uses PackageInfo.versionName. */
    public val appVersion: String? = builder.appVersion

    /**
     * Whether logout, before its unlink, and an account switch (a login of another customer), before the new link, clear
     * this device's tags, also on the server. At a first launch or after a reinstall, a logout keeps the tags set before
     * it in that launch. Default true.
     */
    public val clearTagsOnLogout: Boolean = builder.clearTagsOnLogout

    /**
     * Whether SallaPush waits for [SallaPush.setConsentGiven] before sending or storing anything: until then nothing is
     * written but the consent answer, except the one minimised state a withdrawal saves before its requests. Default
     * false.
     */
    public val requiresUserConsent: Boolean = builder.requiresUserConsent

    /**
     * Whether a tap opens an https launch URL itself instead of the app. Default false: the URL goes to click listeners.
     * When nothing on the device can open the URL, the tap opens the app.
     */
    public val openLaunchUrls: Boolean = builder.openLaunchUrls

    /**
     * Whether SallaPush clears the app icon's badge when the app comes to the foreground. Launchers count an app's
     * notifications in the shade, so SallaPush removes its notifications that show on the badge: on Android 8.0 and later,
     * those on a channel that shows badges. Nothing is removed when the app comes back because the phone was unlocked with
     * the app on screen, or while consent is required and not given; the app's own and other SDKs' notifications stay.
     * Default false: SallaPush's notifications stay in the shade, and on the badge, until the user opens or swipes them
     * away.
     */
    public val autoClearBadge: Boolean = builder.autoClearBadge

    /**
     * Whether SallaPush's notifications count on the app icon's badge: SallaPush creates its notification channels with
     * badges. False creates them without badges, so its notifications add nothing to the badge. Android fixes a channel's
     * badges when the channel is created, so this applies to the channels SallaPush creates from then on. Default true.
     */
    public val autoUpdateBadge: Boolean = builder.autoUpdateBadge

    /** Builds a [SallaPushConfig]. */
    public class Builder {
        /** Tests only: see [SallaPushConfig.apiBaseUrl]. */
        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var apiBaseUrl: String? = null
        internal var logLevel: SallaPushLogLevel? = null
        internal var appVersion: String? = null
        internal var clearTagsOnLogout: Boolean = true
        internal var requiresUserConsent: Boolean = false
        internal var openLaunchUrls: Boolean = false
        internal var autoClearBadge: Boolean = BadgeDefaults.AUTO_CLEAR_BADGE
        internal var autoUpdateBadge: Boolean = BadgeDefaults.AUTO_UPDATE_BADGE

        /** Sets the Logcat level explicitly. */
        public fun setLogLevel(level: SallaPushLogLevel): Builder = entryValue("SallaPushConfig.Builder.setLogLevel", this) {
            if (isNull(level)) {
                EntryGuard.nullArgument("SallaPushConfig.Builder.setLogLevel", "level")
            } else {
                logLevel = level
            }
            this
        }

        /** Sets the reported app version; null uses PackageInfo.versionName. */
        public fun setAppVersion(version: String?): Builder = entryValue("SallaPushConfig.Builder.setAppVersion", this) {
            appVersion = version
            this
        }

        /** Sets whether logout and account switches clear tags. */
        public fun setClearTagsOnLogout(clear: Boolean): Builder = entryValue("SallaPushConfig.Builder.setClearTagsOnLogout", this) {
            clearTagsOnLogout = clear
            this
        }

        /** Sets whether consent is required before SallaPush starts. */
        public fun setRequiresUserConsent(required: Boolean): Builder = entryValue("SallaPushConfig.Builder.setRequiresUserConsent", this) {
            requiresUserConsent = required
            this
        }

        /** Sets whether taps open https launch URLs themselves. */
        public fun setOpenLaunchUrls(open: Boolean): Builder = entryValue("SallaPushConfig.Builder.setOpenLaunchUrls", this) {
            openLaunchUrls = open
            this
        }

        /**
         * Sets whether SallaPush removes its notifications that show on the badge when the app comes to the foreground
         * (off by default).
         */
        public fun setAutoClearBadge(clear: Boolean): Builder = entryValue("SallaPushConfig.Builder.setAutoClearBadge", this) {
            autoClearBadge = clear
            this
        }

        /** Sets whether the channels SallaPush creates show badges, so that its notifications count on the app icon. */
        public fun setAutoUpdateBadge(update: Boolean): Builder = entryValue("SallaPushConfig.Builder.setAutoUpdateBadge", this) {
            autoUpdateBadge = update
            this
        }

        /** Returns the configuration. */
        public fun build(): SallaPushConfig = SallaPushConfig(this)
    }
}
