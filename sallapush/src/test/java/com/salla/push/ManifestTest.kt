package com.salla.push

import android.content.Intent
import android.content.pm.PackageManager
import com.salla.push.internal.log.LogCode
import com.salla.push.testing.Harness
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestTest : SdkTestBase() {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest(): org.w3c.dom.Document {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("sallapush/src/main/AndroidManifest.xml")).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file)
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest().getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun test_servicePriorityNegative() {
        val service = elements("service").single()
        assertEquals("com.salla.push.SallaPushFirebaseMessagingService", service.getAttributeNS(androidNs, "name"))
        val filter = service.getElementsByTagName("intent-filter").item(0) as Element
        assertEquals("-450", filter.getAttributeNS(androidNs, "priority"))
        val action = filter.getElementsByTagName("action").item(0) as Element
        assertEquals("com.google.firebase.MESSAGING_EVENT", action.getAttributeNS(androidNs, "name"))
    }

    @Test
    fun test_componentsNotExported() {
        val components = elements("service") + elements("activity") + elements("receiver")
        assertEquals(4, components.size)
        for (component in components) {
            assertEquals(component.getAttributeNS(androidNs, "name"), "false", component.getAttributeNS(androidNs, "exported"))
        }
        val open = elements("activity").single { it.getAttributeNS(androidNs, "name").endsWith("SallaPushNotificationOpenActivity") }
        assertEquals("true", open.getAttributeNS(androidNs, "noHistory"))
        assertEquals("true", open.getAttributeNS(androidNs, "excludeFromRecents"))
        assertEquals("", open.getAttributeNS(androidNs, "taskAffinity"))
        assertTrue(open.hasAttributeNS(androidNs, "taskAffinity"))
    }

    @Test
    fun test_permissions() {
        val permissions = elements("uses-permission").map { it.getAttributeNS(androidNs, "name") }.toSet()
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.POST_NOTIFICATIONS"),
            permissions,
        )
    }

    @Test
    fun test_mergedManifest_resolvesTheSdkServiceWhenTheAppHasNone() {
        val intent = Intent("com.google.firebase.MESSAGING_EVENT").setPackage(h.context.packageName)
        val resolved = h.context.packageManager.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
        assertTrue(resolved.any { it.serviceInfo.name == SallaPushFirebaseMessagingService::class.java.name })
        val ours = resolved.first { it.serviceInfo.name == SallaPushFirebaseMessagingService::class.java.name }
        assertEquals(-450, ours.filter?.priority ?: ours.priority)
        assertTrue(!ours.serviceInfo.exported)
    }

    @Test
    fun test_anotherServiceReceivesFcm_nothingForwarded_hintOnce_wordedAsACheck() {
        h.fcmServiceName = "com.example.store.AppMessagingService"
        h.start()
        h.advance(11.0)
        val hints = h.capture.of(LogCode.FCM_FORWARDING_HINT)
        assertEquals(1, hints.size)
        assertTrue(hints.single().message.contains("com.example.store.AppMessagingService"))
        assertTrue(hints.single().message.contains("handleFirebaseMessage"))
        // A correctly forwarding app also sees it on launches without a token change or a message: never an order.
        assertTrue(hints.single().message.contains("If it does not forward them to SallaPush yet"))
        SallaPush.initWithContext(h.context, "st_43_android")
        h.advance(11.0)
        assertEquals(1, h.capture.count(LogCode.FCM_FORWARDING_HINT))
    }

    @Test
    fun test_forwardedToken_noHint() {
        h.fcmServiceName = "com.example.store.AppMessagingService"
        h.start()
        SallaPush.Notifications.onNewToken(h.context, Harness.FCM_TOKEN)
        h.advance(11.0)
        assertTrue(!h.capture.has(LogCode.FCM_FORWARDING_HINT))
    }

    @Test
    fun test_forwardedMessage_noHint() {
        h.fcmServiceName = "com.example.store.AppMessagingService"
        h.start()
        SallaPush.Notifications.handleFirebaseMessage(h.context, Messages.remote(mapOf("from_another_sdk" to "1")))
        h.advance(11.0)
        assertTrue(!h.capture.has(LogCode.FCM_FORWARDING_HINT))
    }

    @Test
    fun test_sdkServiceOrASubclassReceivesFcm_noHint() {
        h.fcmServiceName = SallaPushFirebaseMessagingService::class.java.name
        h.start()
        h.advance(11.0)
        assertTrue(!h.capture.has(LogCode.FCM_FORWARDING_HINT))

        val next = Harness(h.context).install()
        next.fcmServiceName = HostMessagingService::class.java.name
        next.start()
        next.advance(11.0)
        assertTrue(!next.capture.has(LogCode.FCM_FORWARDING_HINT))
        next.close()
    }

    /** A host service that extends the SDK's (it forwards through super). */
    class HostMessagingService : SallaPushFirebaseMessagingService()
}
