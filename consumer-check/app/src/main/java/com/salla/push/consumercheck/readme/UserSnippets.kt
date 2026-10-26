package com.salla.push.consumercheck.readme

import com.salla.push.SallaPush

/**
 * The snippets of README.md and docs/REFERENCE.md about login and tags, and the setLanguage call README.md names; the
 * app calls them with its own values.
 */
class UserSnippets {

    fun customerSession(customerId: String, identityToken: String) {
        // snippet:login-logout
        // After the customer signs in:
        SallaPush.login(customerId, identityToken)

        // After the customer signs out:
        SallaPush.logout()
        // end-snippet
    }

    fun cartChanged(total: Double, items: Int) {
        // snippet:tags
        SallaPush.User.addTag("add_to_cart", System.currentTimeMillis().toString())
        SallaPush.User.addTags(mapOf("total_cart_amount" to total.toString(), "cart_items" to items.toString()))
        // end-snippet
    }

    fun cartEmptied() {
        // snippet:remove-tags
        SallaPush.User.removeTags(listOf("total_cart_amount", "cart_items"))
        // end-snippet
    }

    fun languageChanged(languageCode: String) {
        SallaPush.User.setLanguage(languageCode)
    }
}
