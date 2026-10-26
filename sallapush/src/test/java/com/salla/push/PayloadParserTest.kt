package com.salla.push

import com.salla.push.internal.log.LogCode
import com.salla.push.internal.payload.DepthScan
import com.salla.push.internal.payload.PayloadParser
import com.salla.push.internal.util.parseJsonObject
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PayloadParserTest : SdkTestBase() {

    private fun notification(data: Map<String, String>): SallaPushNotification {
        val parsed = PayloadParser.parse(data) ?: throw AssertionError("not SallaPush")
        return h.core.createNotification(parsed, null)
    }

    @Test
    fun test_goldenNative_c3Android() {
        val data = Messages.golden("c3-android") { it.getJSONArray("notifications").getJSONObject(0).getJSONObject("data") }
        assertTrue(SallaPush.Notifications.isSallaPushMessage(data))
        val notification = notification(data)
        assertEquals(SallaPushPayloadSource.NATIVE, notification.source)
        // msg_test_002 is not hex: still native and displayed, but not attributable (no events).
        assertNull(notification.notificationId)
        assertEquals("تم شحن طلبك", notification.title)
        assertEquals("طلبك رقم 987 في الطريق", notification.body)
        assertEquals("https://cdn.salla.sa/x.jpg", notification.imageUrl)
        assertEquals("salla://orders/987", notification.launchUrl)
        assertEquals("order-987", notification.collapseId)
        assertEquals("orders", notification.channelId)
        assertNull(notification.sound)
        assertEquals(mapOf("orderId" to "987"), notification.additionalData)
        assertEquals(987L, notification.getLong("orderId"))
        assertEquals("987", notification.getString("orderId"))
        assertEquals(data, notification.rawPayload)
        assertEquals(com.salla.push.internal.SdkCore.stableId("order-987"), notification.androidNotificationId)
    }

    @Test
    fun test_goldenCompat_android() {
        val data = Messages.golden("compat-android") { it.getJSONObject("data") }
        val notification = notification(data)
        assertEquals(SallaPushPayloadSource.COMPAT, notification.source)
        assertEquals("msg_1", notification.notificationId)
        assertEquals("تم شحن طلبك", notification.title)
        assertEquals("طلبك رقم 987 في الطريق", notification.body)
        assertNull(notification.subtitle)
        assertEquals("https://cdn.salla.sa/x.jpg", notification.imageUrl)
        assertEquals("https://s.salla.sa/o/987", notification.launchUrl)
        assertEquals("orders", notification.sound)
        assertEquals("orders", notification.channelId)
        assertEquals(mapOf("type" to "order", "id" to "987"), notification.additionalData)
        assertEquals(987L, notification.getLong("id"))
    }

    @Test
    fun test_goldenCompat_typedData_keepsTypes() {
        val data = Messages.golden("compat-android-typed-data") { it.getJSONObject("data") }
        val notification = notification(data)
        assertEquals(987, notification.additionalData["id"])
        assertEquals(true, notification.additionalData["flag"])
        assertEquals(987L, notification.getLong("id"))
        assertEquals(true, notification.getBoolean("flag"))
        assertEquals("987", notification.getString("id"))
        assertEquals("true", notification.getString("flag"))
        assertFalse(notification.additionalData.containsKey("push_ref"))
    }

    @Test
    fun test_goldenCompatPushRef_android() {
        val data = Messages.golden("compat-push-ref") { it.getJSONObject("android").getJSONObject("data") }
        assertEquals("msg_0123456789abcdef0123456789abcdef", notification(data).notificationId)
    }

    @Test
    fun test_foreignPayloads_areNotSallaPush() {
        val oneSignalWithoutRef = mapOf("custom" to JSONObject().put("i", "x").put("a", JSONObject().put("id", 1)).toString(), "alert" to "Hi")
        val oneSignalBadRef = mapOf("custom" to JSONObject().put("i", "x").put("a", JSONObject().put("push_ref", "ref-1")).toString())
        val moEngage = mapOf("push_from" to "moengage", "gcm_title" to "Hi", "gcm_alert" to "Body", "moe_app_id" to "X")
        val webEngage = mapOf("source" to "webengage", "message_data" to "{\"title\":\"Hi\"}")
        val plain = mapOf("title" to "Hello", "message" to "World")
        for (data in listOf(oneSignalWithoutRef, oneSignalBadRef, moEngage, webEngage, plain, emptyMap())) {
            assertFalse(data.toString(), SallaPush.Notifications.isSallaPushMessage(data))
            assertNull(PayloadParser.parse(data))
            assertFalse(SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(data)))
        }
        h.settle()
        assertTrue(h.capture.has(LogCode.NOTIF_FOREIGN))
    }

    @Test
    fun test_unknownVersion_parsedBestEffort_warnsOnce() {
        val data = Messages.native() + ("sp_v" to "2")
        assertTrue(SallaPush.Notifications.isSallaPushMessage(data))
        assertTrue(PayloadParser.parse(data)?.unknownVersion == true)
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(data))
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(data))
        h.settle()
        assertEquals(1, h.capture.count(LogCode.NOTIF_UNKNOWN_VERSION))
    }

    @Test
    fun test_nativeAdditionalData_excludesSpKeys_andHttpImageIgnored() {
        val data = Messages.native(extra = mapOf("type" to "product", "id" to "15")) + mapOf("sp_image" to "http://insecure.example/x.png", "sp_future" to "1")
        val notification = notification(data)
        assertEquals(mapOf("type" to "product", "id" to "15"), notification.additionalData)
        assertNull(notification.imageUrl)
    }

    @Test
    fun test_deepCustom_isRejectedByDepthScan_withoutRecursion() {
        val depth = 2000
        val deep = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        val custom = "{\"i\":\"x\",\"a\":{\"push_ref\":\"msg_1\",\"d\":$deep}}"
        assertFalse(DepthScan.isSafe(custom, 32))
        assertFalse(SallaPush.Notifications.isSallaPushMessage(mapOf("custom" to custom)))
        var parsed: Any? = "not run"
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                parsed = PayloadParser.parse(mapOf("custom" to custom))
            } catch (t: Throwable) {
                failure = t
            }
        }, "small-stack", 512 * 1024)
        thread.start()
        thread.join()
        assertNull(failure)
        assertNull(parsed)
    }

    @Test
    fun test_depthScan_ignoresBracketsInStrings() {
        assertTrue(DepthScan.isSafe("{\"a\":\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\\\"\"}", 2))
        assertTrue(DepthScan.isSafe("[[]]", 2))
        assertFalse(DepthScan.isSafe("[[[]]]", 2))
        assertFalse(DepthScan.isSafe("[[[", 2))
    }

    @Test
    fun test_depthScan_acceptsStrictJsonOnly_soOrgJsonLeniencyCannotHideNesting() {
        val nested = "[".repeat(40) + "]".repeat(40)
        // org.json parses every one of these; a quote-aware bracket count would see no nesting in any of them.
        val bypasses = listOf(
            "{\"x\":'\"',\"y\":$nested,\"z\":'\"'}",
            "{\"x\":1 /* \" */,\"y\":$nested /* \" */}",
            "{\"x\":1, // \"\n\"y\":$nested}",
            "{\"x\":1, # \"\n\"y\":$nested}",
            "{\"x\":a\"b,\"y\":$nested,\"z\":c\"d}",
        )
        for (text in bypasses) {
            assertFalse(text, DepthScan.isSafe(text, 32))
            assertNull(text, parseJsonObject(text))
        }
        // Lenient syntax is refused even when shallow: org.json never sees anything but strict JSON.
        assertNull(parseJsonObject("{'a':1}"))
        assertNull(parseJsonObject("{a:1}"))
        assertTrue(DepthScan.isSafe("{\"a\":[1,-2.5e+3,0,0.5,1E9,true,false,null,\"\\u00e9\\n\\/\"],\"b\":{},\"c\":[]}", 32))
        assertTrue(DepthScan.isSafe(" [ ] ", 1))
        assertTrue(DepthScan.isSafe("\"text\"", 0))
        val malformed = listOf(
            "", " ", "{", "}", "{\"a\"}", "{\"a\":}", "[1,]", "{,}", "01", "1.", "-", "1e", "\"\\x\"", "\"a",
            "tru", "nul", "{\"a\":1}x", "[1 2]", "{\"a\":1,}", "\"\u0001\"", "[\"\\u12\"]", "+1", ".5",
        )
        for (text in malformed) assertFalse(text, DepthScan.isSafe(text, 32))
    }

    @Test
    fun test_nestedCompatData_isCutAtDepth32() {
        var nested: Any = "leaf"
        for (index in 0 until 30) nested = JSONObject().put("n", nested)
        val custom = JSONObject().put("i", "x").put("a", JSONObject().put("push_ref", "msg_1").put("tree", nested)).toString()
        val notification = notification(mapOf("custom" to custom))
        var current: Any? = notification.additionalData["tree"]
        var levels = 0
        while (current is Map<*, *>) {
            current = current["n"]
            levels += 1
        }
        assertTrue(levels in 1..31)
    }

    @Test
    fun test_oversizedCustom_isForeign() {
        val big = "x".repeat(5000)
        val custom = JSONObject().put("i", "x").put("a", JSONObject().put("push_ref", "msg_1").put("big", big)).toString()
        assertFalse(SallaPush.Notifications.isSallaPushMessage(mapOf("custom" to custom)))
    }

    @Test
    fun test_typedAccessors() {
        val custom = JSONObject().put("i", "x").put("a", JSONObject()
            .put("push_ref", "msg_1")
            .put("int", 5).put("double", 5.0).put("fraction", 5.5).put("numeric", " 42 ").put("decimal", "7.0")
            .put("t", "true").put("one", "1").put("zero", 0).put("bad", "yes").put("obj", JSONObject().put("x", 1))).toString()
        val notification = notification(mapOf("custom" to custom))
        assertEquals(5L, notification.getLong("int"))
        assertEquals(5L, notification.getLong("double"))
        assertNull(notification.getLong("fraction"))
        assertEquals(42L, notification.getLong("numeric"))
        assertEquals(7L, notification.getLong("decimal"))
        assertEquals(true, notification.getBoolean("t"))
        assertEquals(true, notification.getBoolean("one"))
        assertEquals(false, notification.getBoolean("zero"))
        assertNull(notification.getBoolean("bad"))
        assertNull(notification.getString("obj"))
        assertEquals("5.5", notification.getString("fraction"))
        assertEquals("5", notification.getString("double"))
        assertNull(notification.getString("missing"))
    }

    @Test
    fun test_fuzz_randomMaps_neverThrow() {
        val random = Random(7)
        val alphabet = "{}[]\":,\\ab_msg01xyz \n\u0000😀"
        repeat(1000) {
            val data = LinkedHashMap<String, String>()
            repeat(random.nextInt(6)) {
                val key = listOf("sp_v", "sp_id", "sp_title", "custom", "title", "alert", "x").random(random)
                val length = random.nextInt(200)
                data[key] = (0 until length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            }
            PayloadParser.isSallaPush(data)
            PayloadParser.parse(data)?.let { assertNotNull(h.core.createNotification(it, null)) }
        }
    }
}
