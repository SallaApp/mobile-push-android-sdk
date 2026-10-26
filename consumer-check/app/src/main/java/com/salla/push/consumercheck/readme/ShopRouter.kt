package com.salla.push.consumercheck.readme

import android.app.Application
import android.content.Intent

/**
 * docs/REFERENCE.md's router: the screens a click listener added in Application.onCreate opens, from the Application.
 */
// snippet:router
class ShopRouter(private val application: Application) : Router {

    override fun showProduct(id: Long) = show(Intent(application, ProductActivity::class.java).putExtra("id", id))

    override fun showOrder(id: String) = show(Intent(application, OrderActivity::class.java).putExtra("id", id))

    override fun openUrl(url: String) = show(Intent(application, WebActivity::class.java).putExtra("url", url))

    private fun show(intent: Intent) {
        // The Application has no task of its own: this flag opens the screen in your app's task, on top.
        application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
// end-snippet
