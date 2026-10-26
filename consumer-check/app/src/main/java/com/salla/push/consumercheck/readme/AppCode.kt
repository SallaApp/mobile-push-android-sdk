package com.salla.push.consumercheck.readme

import android.app.Activity

// The app's own code that the snippets of README.md and docs/ call: its navigation, screens, checkout flow and error
// reporting. Declarations only, so that every snippet compiles exactly as the docs show it against the published AAR.

/** The app's navigation. */
interface Router {

    fun showProduct(id: Long)

    fun showOrder(id: String)

    fun openUrl(url: String)
}

/** The app's product screen. */
class ProductActivity : Activity()

/** The app's order screen. */
class OrderActivity : Activity()

/** The app's in-app browser screen. */
class WebActivity : Activity()

/** The app's checkout flow. */
interface Checkout {

    val isInProgress: Boolean

    fun whenFinished(action: () -> Unit)
}

/** The app's error reporting. */
interface Telemetry {

    fun report(source: String, code: String, message: String, metadata: Map<String, String>)
}
