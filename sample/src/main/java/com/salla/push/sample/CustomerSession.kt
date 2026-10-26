package com.salla.push.sample

import android.content.Context
import android.content.SharedPreferences
import com.salla.push.SallaPush

/**
 * The sample's stand-in for your app's session: the signed-in customer's id and their Salla token, kept in the
 * sample's own preferences. Your app keeps its session its own way; SallaPush only needs to hear of every change.
 */
object CustomerSession {

    private const val PREFERENCES = "sample_session"
    private const val CUSTOMER_ID = "customer_id"
    private const val IDENTITY_TOKEN = "identity_token"

    /** The signed-in customer's id, or null while nobody is signed in. */
    fun customerId(context: Context): String? = preferences(context).getString(CUSTOMER_ID, null)

    /** The signed-in customer's identity token, or null while nobody is signed in. */
    fun identityToken(context: Context): String? = preferences(context).getString(IDENTITY_TOKEN, null)

    fun signIn(context: Context, customerId: String, identityToken: String) {
        save(context, customerId, identityToken)
        SallaPush.login(customerId, identityToken)
    }

    fun signOut(context: Context) {
        save(context, null, null)
        SallaPush.logout()
    }

    private fun save(context: Context, customerId: String?, identityToken: String?) {
        preferences(context).edit().putString(CUSTOMER_ID, customerId).putString(IDENTITY_TOKEN, identityToken).apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
