package com.salla.push.testing

import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.internal.display.NotificationDisplayer
import org.json.JSONObject

/** FCM messages and golden payloads for tests. */
internal object Messages {

    fun native(
        id: String = "msg_0a1b2c3d",
        title: String? = "تم شحن طلبك",
        body: String? = "طلبك رقم 987 في الطريق",
        extra: Map<String, String> = mapOf("orderId" to "987"),
    ): Map<String, String> {
        val data = LinkedHashMap<String, String>()
        data["sp_v"] = "1"
        data["sp_id"] = id
        title?.let { data["sp_title"] = it }
        body?.let { data["sp_body"] = it }
        data.putAll(extra)
        return data
    }

    fun compat(pushRef: String = "msg_1", title: String = "T", alert: String = "B"): Map<String, String> = mapOf(
        "custom" to JSONObject()
            .put("i", "7d3f1c2a-0000-4000-8000-000000000001")
            .put("a", JSONObject().put("type", "order").put("id", "987").put("push_ref", pushRef))
            .put("u", "https://s.salla.sa/o/987")
            .toString(),
        "title" to title,
        "alert" to alert,
    )

    fun remote(data: Map<String, String>): RemoteMessage = RemoteMessage.Builder("sallapush@fcm.googleapis.com").setData(data).build()

    /** The `data` map of a vendored golden (the SallaPush server's send request, as FCM delivers its data). */
    fun golden(name: String, path: (JSONObject) -> JSONObject): Map<String, String> {
        val stream = Messages::class.java.classLoader?.getResourceAsStream("golden/$name.json") ?: throw AssertionError("missing golden $name")
        val json = JSONObject(stream.bufferedReader().use { it.readText() })
        val data = path(json)
        val result = LinkedHashMap<String, String>()
        for (key in data.keysList()) result[key] = data.getString(key)
        return result
    }

    fun goldenText(name: String): String {
        val stream = Messages::class.java.classLoader?.getResourceAsStream("golden/$name.json") ?: throw AssertionError("missing golden $name")
        return stream.bufferedReader().use { it.readText() }
    }

    /** The intent a SallaPush notification's content intent carries. */
    fun openIntent(harness: Harness, data: Map<String, String>, androidId: Int = 42, nonce: String = "nonce-1"): Intent =
        Intent(harness.context, com.salla.push.internal.click.SallaPushNotificationOpenActivity::class.java)
            .putExtra(NotificationDisplayer.EXTRA_PAYLOAD, NotificationDisplayer.payloadBundle(data))
            .putExtra(NotificationDisplayer.EXTRA_ANDROID_ID, androidId)
            .putExtra(NotificationDisplayer.EXTRA_NONCE, nonce)
}
