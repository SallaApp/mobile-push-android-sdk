package com.salla.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.net.TrafficStats
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.display.ConnectionOpener
import com.salla.push.internal.display.DisplayResult
import com.salla.push.internal.display.ImageLoader
import com.salla.push.internal.display.ImageResult
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.display.SmallIcon
import com.salla.push.internal.payload.PayloadParser
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.util.SALLAPUSH_SOCKET_TAG
import com.salla.push.testing.Messages
import com.salla.push.testing.SdkTestBase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The notification picture download against a real HTTP server: one budget (8 s in production) bounds DNS, connect,
 * headers, redirects and the body together, and the caller never waits longer.
 */
class ImageLoaderTest : SdkTestBase() {

    private val server = MockWebServer()
    private val socketTags = CopyOnWriteArrayList<Int>()

    @Before
    fun startServer() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    /** https URLs on "images.test" are served by the local server over plain HTTP; every other host is refused. */
    private fun loader(budgetMillis: Long = ImageLoader.BUDGET_MILLIS): ImageLoader = ImageLoader(
        ConnectionOpener { url ->
            socketTags.add(TrafficStats.getThreadStatsTag())
            if (url.host != "images.test") null else URL("http://127.0.0.1:${server.port}${url.file}").openConnection() as HttpURLConnection
        },
        budgetMillis,
    )

    @Test
    fun test_slowHeadersThenATrickledBody_endAtTheEightSecondBudget() {
        server.enqueue(
            MockResponse().setHeadersDelay(7_500, TimeUnit.MILLISECONDS)
                .setBody(Buffer().write(ByteArray(64 * 1024)))
                .throttleBody(1_024, 1, TimeUnit.SECONDS),
        )
        val started = System.nanoTime()
        assertEquals("not loaded within 8 s", failure(loader().fetch("https://images.test/slow.png")))
        val elapsed = millisSince(started)
        assertTrue("took $elapsed ms", elapsed in 7_900L..8_900L)
    }

    @Test
    fun test_aBodyThatStallsAfterTheHeaders_isCutAtTheBudget_notAtASocketTimeout() {
        // The headers come in time, then the body stalls inside one read: the caller stops waiting when the budget is
        // spent and closes the connection, whatever socket timeout that read started with.
        server.enqueue(
            MockResponse().setHeadersDelay(1_000, TimeUnit.MILLISECONDS)
                .setBodyDelay(4_000, TimeUnit.MILLISECONDS)
                .setBody(Buffer().write(PNG)),
        )
        val started = System.nanoTime()
        assertEquals("not loaded within 1.5 s", failure(loader(budgetMillis = 1_500).fetch("https://images.test/stall.png")))
        val elapsed = millisSince(started)
        assertTrue("took $elapsed ms", elapsed in 1_400L..2_100L)
    }

    @Test
    fun test_aRedirectChain_staysWithinTheBudget() {
        for (next in listOf("/b", "/c", "/d")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://images.test$next").setHeadersDelay(600, TimeUnit.MILLISECONDS))
        }
        server.enqueue(MockResponse().setBody(Buffer().write(PNG)).setHeadersDelay(600, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        assertEquals("not loaded within 1.5 s", failure(loader(budgetMillis = 1_500).fetch("https://images.test/a")))
        val elapsed = millisSince(started)
        assertTrue("took $elapsed ms", elapsed in 1_400L..2_300L)
    }

    @Test
    fun test_redirects_areFollowedOnlyToHttps_andAtMostThreeTimes() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://images.test/insecure"))
        assertEquals("redirected (HTTP 302) to a URL that is not https", failure(loader().fetch("https://images.test/a")))
        assertEquals(1, server.requestCount)

        repeat(ImageLoader.MAX_REDIRECTS + 1) { server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/again")) }
        assertEquals("more than 3 redirects", failure(loader().fetch("https://images.test/b")))
        assertEquals(1 + ImageLoader.MAX_REDIRECTS + 1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/picture.png"))
        server.enqueue(MockResponse().setBody(Buffer().write(PNG)))
        assertTrue(loader().fetch("https://images.test/c") is ImageResult.Loaded)
        assertEquals("not an https URL", failure(loader().fetch("http://images.test/plain.png")))
    }

    @Test
    fun test_aPicture_isDecoded_overATaggedSocket() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG)))
        val picture = (loader().fetch("https://images.test/p.png") as? ImageResult.Loaded)?.bitmap
        assertNotNull(picture)
        assertEquals(1, picture?.width)
        assertEquals(listOf(SALLAPUSH_SOCKET_TAG), socketTags)
    }

    @Test
    fun test_aDeclaredOrActualBodyOver5Mb_isRefused() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(ImageLoader.MAX_BYTES + 1))))
        assertEquals("larger than 5 MB", failure(loader().fetch("https://images.test/huge.png")))
    }

    @Test
    fun test_aFailedDownload_saysWhy() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        assertEquals("HTTP 404", failure(loader().fetch("https://images.test/gone.png")))
        assertEquals("could not open a connection", failure(loader().fetch("https://elsewhere.test/p.png")))
        assertEquals("not an https URL", failure(loader().fetch("http://images.test/p.png")))
        val refused = ImageLoader(ConnectionOpener { URL("http://127.0.0.1:1/p.png").openConnection() as HttpURLConnection })
        assertEquals("network: ConnectException", failure(refused.fetch("https://images.test/p.png")))
        // An error that escapes the download is named too, never reported as the budget running out.
        val broken = ImageLoader(ConnectionOpener { throw LinkageError("a broken class path") })
        assertEquals("the download failed (LinkageError)", failure(broken.fetch("https://images.test/p.png")))
    }

    @Test
    fun test_aPictureThatCannotBeUsed_isReportedOnce_andTheNotificationIsShownAsText() {
        // The console prints once the level is known (initWithContext reads it from the manifest): the default, WARN.
        h.logger.setManifestLevel(null)
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val displayer = NotificationDisplayer(h.logger, loader(), SmallIcon(h.logger))
        val parsed = PayloadParser.parse(Messages.native(extra = mapOf("sp_image" to "https://images.test/gone.png"))) ?: throw AssertionError("not parsed")
        assertEquals(DisplayResult.SHOWN, displayer.show(h.context, h.core.createNotification(parsed, 42), "nonce-1", ManifestConfig.EMPTY, true))
        val report = h.capture.of(LogCode.NOTIF_IMAGE_UNUSABLE).single()
        assertEquals(SallaPushLogLevel.WARN, report.code.level)
        assertTrue(report.message, report.message.contains("picture") && report.message.contains("HTTP 404"))
        assertTrue(h.console.single { it.contains("notif.image_unusable") }.startsWith("WARN"))
    }

    @Test
    fun test_aServerThatNeverAnswers_stillPostsTheNotificationAsTextWithinTheBudget() {
        shadowOf(h.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val displayer = NotificationDisplayer(h.logger, loader(), SmallIcon(h.logger))
        val parsed = PayloadParser.parse(Messages.native(extra = mapOf("sp_image" to "https://images.test/slow.png"))) ?: throw AssertionError("not parsed")
        val started = System.nanoTime()
        val result = displayer.show(h.context, h.core.createNotification(parsed, 42), "nonce-1", ManifestConfig.EMPTY, true)
        val elapsed = millisSince(started)
        assertEquals(DisplayResult.SHOWN, result)
        assertTrue("took $elapsed ms", elapsed in 7_900L..8_900L)
        val posted = shadowOf(h.context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertFalse(posted.extras.containsKey(Notification.EXTRA_PICTURE))
    }

    private fun millisSince(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun failure(result: ImageResult): String = (result as? ImageResult.Failed)?.reason ?: throw AssertionError("a picture was loaded")

    private companion object {
        /** A valid 1x1 PNG. */
        val PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")
    }
}
