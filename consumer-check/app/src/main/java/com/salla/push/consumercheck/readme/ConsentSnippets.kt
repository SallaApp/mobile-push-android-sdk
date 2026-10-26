package com.salla.push.consumercheck.readme

import com.salla.push.SallaPush

/** docs/REFERENCE.md's consent snippet: the app records the user's privacy choice whenever they make it. */
class ConsentSnippets {

    // snippet:consent-given
    fun privacyChoiceChanged(allowsNotifications: Boolean) {
        SallaPush.setConsentGiven(allowsNotifications)
    }
    // end-snippet
}
