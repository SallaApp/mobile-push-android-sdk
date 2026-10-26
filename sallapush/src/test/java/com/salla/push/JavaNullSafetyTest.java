package com.salla.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.firebase.messaging.RemoteMessage;
import com.salla.push.internal.api.Routes;
import com.salla.push.internal.log.LogCode;
import com.salla.push.internal.log.LogRecord;
import com.salla.push.internal.payload.ParsedPayload;
import com.salla.push.internal.payload.PayloadParser;
import com.salla.push.testing.EmittedCodes;
import com.salla.push.testing.Harness;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A Java app passing null for every reference parameter of the public API, before and after initialisation: no call
 * throws (Kotlin's null-check intrinsics are compiled out and every entry is guarded), each one logs instead, and the
 * SDK keeps working afterwards.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class JavaNullSafetyTest {

    private Harness harness;
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        harness = new Harness(context, null).install();
    }

    @After
    public void tearDown() {
        harness.close();
    }

    @AfterClass
    public static void writeEmittedCodes() {
        EmittedCodes.INSTANCE.flush();
    }

    @Test
    public void test_everyPublicMethod_withNulls_beforeInit_doesNotThrow() {
        callEverythingWithNulls();
        harness.settle();
        assertTrue(harness.getCapture().has(LogCode.API_NULL_ARGUMENT));
        assertFalse(SallaPush.isInitialized());
    }

    @Test
    public void test_everyPublicMethod_withNulls_afterInit_doesNotThrow_andTheSdkKeepsWorking() {
        harness.healthyServer();
        harness.start(Harness.APP_ID, null);
        harness.deliverToken(Harness.FCM_TOKEN);
        harness.advance(2.0);
        callEverythingWithNulls();
        harness.advance(15.0);
        assertTrue(harness.getCapture().has(LogCode.API_NULL_ARGUMENT));
        List<String> nullLogins = new ArrayList<>();
        for (LogRecord record : harness.getCapture().of(LogCode.API_NULL_ARGUMENT)) {
            if (record.getMessage().startsWith("login ")) nullLogins.add(record.getMessage());
        }
        assertEquals(
                Arrays.asList(
                        "login was called with a null externalId (from Java); ignored.",
                        "login was called with a null externalId (from Java); ignored.",
                        "login was called with a null identityToken (from Java); ignored."),
                nullLogins);
        assertFalse(SallaPush.getUser().isLoggedIn());

        SallaPush.login(Harness.CUSTOMER_ID, Harness.CUSTOMER_TOKEN);
        SallaPush.getUser().addTag("plan", "gold");
        harness.advance(15.0);
        assertTrue(SallaPush.isInitialized());
        assertEquals(Harness.APP_ID, SallaPush.getAppId());
        assertEquals(1, harness.getNet().calls(Routes.LINK).size());
        assertEquals("gold", SallaPush.getUser().getTags().get("plan"));
    }

    @Test
    public void test_initWithNullContextOrAppId_staysUninitialised() {
        SallaPush.initWithContext(null, null);
        SallaPush.initWithContext(null, Harness.APP_ID);
        SallaPush.initWithContext(context, null);
        SallaPush.initWithContext(null, null, null);
        SallaPush.initWithContext(null, Harness.APP_ID, new SallaPushConfig.Builder().build());
        SallaPush.initWithContext(context, null, null);
        harness.settle();
        assertFalse(SallaPush.isInitialized());
        assertNull(SallaPush.getAppId());
    }

    @Test
    public void test_initWithNullConfig_usesTheDefaults() {
        SallaPush.initWithContext(context, Harness.APP_ID, null);
        harness.settle();
        assertTrue(SallaPush.isInitialized());
        assertEquals(Harness.APP_ID, SallaPush.getAppId());
    }

    @Test
    public void test_configBuilder_withNulls_keepsTheDefaults() {
        SallaPushConfig config = new SallaPushConfig.Builder()
                .setLogLevel(null)
                .setAppVersion(null)
                .build();
        assertNull(config.getLogLevel());
        assertNull(config.getAppVersion());
        assertTrue(config.getClearTagsOnLogout());
        assertFalse(config.getRequiresUserConsent());
        assertFalse(config.getOpenLaunchUrls());
        assertFalse(config.getAutoClearBadge());
        assertTrue(config.getAutoUpdateBadge());
    }

    @Test
    public void test_configBuilder_badgeSettings_fromJava() {
        SallaPushConfig config = new SallaPushConfig.Builder()
                .setAutoClearBadge(true)
                .setAutoUpdateBadge(false)
                .build();
        assertTrue(config.getAutoClearBadge());
        assertFalse(config.getAutoUpdateBadge());
    }

    @Test
    public void test_notificationModel_withNullKeys_returnsNull() {
        Map<String, String> data = new HashMap<>();
        data.put("sp_v", "1");
        data.put("sp_id", "msg_0a1b2c3d");
        data.put("sp_title", "T");
        data.put("orderId", "987");
        ParsedPayload parsed = PayloadParser.INSTANCE.parse(data);
        assertNotNull(parsed);
        SallaPushNotification notification = harness.getCore().createNotification(parsed, null);
        assertNull(notification.getString(null));
        assertNull(notification.getLong(null));
        assertNull(notification.getBoolean(null));
        assertEquals("987", notification.getString("orderId"));
        notification.display();
        harness.settle();
    }

    @Test
    public void test_messagingService_withNulls_doesNotThrow() {
        SallaPushFirebaseMessagingService service = Robolectric.buildService(SallaPushFirebaseMessagingService.class).create().get();
        service.onMessageReceived(null);
        service.onNewToken(null);
        harness.settle();
    }

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    private void callEverythingWithNulls() {
        SallaPush.login(null, null);
        SallaPush.login(null, Harness.CUSTOMER_TOKEN);
        SallaPush.login(Harness.CUSTOMER_ID, null);

        SallaPushUser user = SallaPush.getUser();
        user.addTag(null, null);
        user.addTag("plan", null);
        user.addTag(null, "gold");
        user.addTags(null);
        Map<String, String> withNulls = new HashMap<>();
        withNulls.put(null, "v");
        withNulls.put("k", null);
        user.addTags(withNulls);
        user.removeTag(null);
        user.removeTags(null);
        user.removeTags(Arrays.asList("a", null));
        user.setLanguage(null);
        user.addObserver(null);
        user.removeObserver(null);
        assertNotNull(user.getTags());
        user.getExternalId();
        user.isLoggedIn();

        SallaPushSubscription subscription = user.getPushSubscription();
        subscription.addObserver(null);
        subscription.removeObserver(null);
        subscription.getId();
        subscription.getToken();
        subscription.getOptedIn();

        SallaPushNotifications notifications = SallaPush.getNotifications();
        notifications.addClickListener(null);
        notifications.removeClickListener(null);
        notifications.addForegroundLifecycleListener(null);
        notifications.removeForegroundLifecycleListener(null);
        notifications.addPermissionObserver(null);
        notifications.removePermissionObserver(null);
        notifications.requestPermission(false, null);
        notifications.requestPermissionSuspend(false, null);
        assertFalse(notifications.isSallaPushMessage((RemoteMessage) null));
        assertFalse(notifications.isSallaPushMessage((Map<String, String>) null));
        Map<String, String> odd = new HashMap<>();
        odd.put(null, null);
        odd.put("sp_v", null);
        assertFalse(notifications.isSallaPushMessage(odd));
        assertFalse(notifications.handleFirebaseMessage(null, null));
        assertFalse(notifications.handleFirebaseMessage(context, null));
        RemoteMessage message = new RemoteMessage.Builder("sallapush@fcm.googleapis.com").addData("sp_v", "1").addData("sp_id", "msg_ab").build();
        notifications.handleFirebaseMessage(null, message);
        notifications.onNewToken(null, null);
        notifications.onNewToken(context, null);
        notifications.onNewToken(null, Harness.FCM_TOKEN_2);
        assertFalse(notifications.trackOpened(null));
        notifications.removeNotification(0);
        notifications.clearAllNotifications();
        notifications.refreshPermission();
        notifications.getPermission();
        notifications.canRequestPermission();

        SallaPushDebug debug = SallaPush.getDebug();
        debug.setLogLevel(null);
        debug.addLogListener(null);
        debug.addLogListener(null, null);
        debug.addLogListener(event -> { }, null);
        debug.removeLogListener(null);
        assertNotNull(debug.getLogLevel());

        SallaPushMigration migration = SallaPush.getMigration();
        migration.setDisplayCompatPayloads(true);

        SallaPush.getSdkVersion();
        SallaPush.getAppId();
        SallaPush.isInitialized();
    }
}
