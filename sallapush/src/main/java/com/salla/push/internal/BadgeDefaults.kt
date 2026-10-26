package com.salla.push.internal

import com.salla.push.SallaPushConfig

/** The defaults of the badge settings, each in this one place. */
internal object BadgeDefaults {
    /** [SallaPushConfig.autoClearBadge] when the app sets nothing. */
    const val AUTO_CLEAR_BADGE: Boolean = false

    /** [SallaPushConfig.autoUpdateBadge] when the app sets nothing, also for a notification shown before initWithContext. */
    const val AUTO_UPDATE_BADGE: Boolean = true
}
