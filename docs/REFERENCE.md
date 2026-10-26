# SallaPush Android SDK: API reference

The calls, listeners and settings an app uses, from Kotlin and Java. Install and set up SallaPush first, as the
[README](../README.md) shows.

## Contents

1. [Initialization](#initialization)
2. [Configuration](#configuration)
3. [Login and logout](#login-and-logout)
4. [Tags and language](#tags-and-language)
5. [Push subscription](#push-subscription)
6. [Notifications](#notifications)
7. [Badges](#badges)
8. [Consent](#consent)
9. [Java](#java)
10. [Your FCM service](#your-fcm-service)
11. [WorkManager](#workmanager)
12. [Privacy](#privacy)

## Initialization

- **`SallaPush.initWithContext(context, appId)`**, or `initWithContext(context, appId, config)` with a
  [configuration](#configuration). Call it in `Application.onCreate`, and name your `Application` class in the
  manifest's `android:name`. It returns at once, and calls made before it are kept and run in order.
- **Every call is safe** from any thread, before or after initialization, and never throws: invalid input, a Java
  `null` included, is logged and ignored.
- **An invalid app id** (anything but 1 to 64 letters, digits, `_` and `-`) logs `init.invalid_app_id`, and SallaPush
  stays off. A second `initWithContext` with the same app id changes nothing, its configuration included.
- **Other processes.** In any process but your app's main one, SallaPush only shows notifications. Keep your
  `FirebaseMessagingService` in the main process, as it is by default.
- **`SallaPush.isInitialized`, `SallaPush.appId` and `SallaPush.sdkVersion`** report the state.

## Configuration

`SallaPushConfig.Builder` sets the options. This configuration logs everything in debuggable builds, and only warnings
and errors in release builds:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ApplicationSnippets.kt#configuration -->
```kotlin
val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
val config = SallaPushConfig.Builder()
    .setLogLevel(if (debuggable) SallaPushLogLevel.VERBOSE else SallaPushLogLevel.WARN)
    .build()
SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002", config)
```

| Builder method | Default | Meaning |
|---|---|---|
| `setLogLevel(level)` | meta-data `com.salla.push.LOG_LEVEL`, else `WARN` | The Logcat level ([Logging](LOGGING.md#logging)) |
| `setAppVersion(version)` | the app's `versionName` | The app version SallaPush reports |
| `setClearTagsOnLogout(clear)` | `true` | Delete the device's tags at a logout, and before a login for another customer ([Tags](#tags-and-language)) |
| `setRequiresUserConsent(required)` | `false` | Wait for `setConsentGiven(true)` ([Consent](#consent)) |
| `setOpenLaunchUrls(open)` | `false` | Open https launch URLs on tap ([Launch URLs](#launch-urls)) |
| `setAutoClearBadge(clear)` | `false` | Remove SallaPush's notifications from the shade when the app comes to the foreground ([Badges](#badges)) |
| `setAutoUpdateBadge(update)` | `true` | Create SallaPush's notification channels with badges ([Badges](#badges)) |

The manifest meta-data SallaPush reads, inside `<application>`; all are optional except the icon:

| Key | Value |
|---|---|
| `com.salla.push.default_notification_icon` | The monochrome small icon, as `android:resource`. Required; without it, Firebase's default icon, else a bell. |
| `com.salla.push.default_channel_id` | The default notification channel's id, as `android:value` |
| `com.salla.push.default_channel_name` | That channel's name in the system settings, as `android:value` or a string `android:resource` |
| `com.salla.push.LOG_LEVEL` | `off`, `error`, `warn`, `info`, `debug` or `verbose` |

The configuration wins over the meta-data.

## Login and logout

The [README](../README.md#login-and-logout) shows both calls.

- **`login(externalId, identityToken)`** links the device to the customer. `externalId` is the customer's id as Salla
  knows it (1 to 128 characters), and `identityToken` the customer token your app received at sign-in, without a
  `Bearer ` prefix. Call it after the customer signs in, at each app launch while they are signed in, and when your
  app refreshes the token: SallaPush keeps the token in memory only.
- **`logout()`** unlinks the device: call it on every sign-out path. It works offline, and the device stays registered
  as a guest. Call it before the next customer's `login`.
- **`SallaPush.User.isLoggedIn` and `SallaPush.User.externalId`** reflect your last `login` or `logout` as soon as it
  returns.
- **The user observer** reports login state changes in `onUserStateChange`, and refused logins in `onLoginFailure`. It
  has two methods, so in Kotlin implement it with `object : SallaPushUserObserver { … }`.

### Login failures

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ApplicationSnippets.kt#login-failures -->
```kotlin
SallaPush.User.addObserver(object : SallaPushUserObserver {
    override fun onUserStateChange(state: SallaPushUserChangedState) {}

    override fun onLoginFailure(failure: SallaPushLoginFailure) {
        when (failure) {
            // Salla refused this token for this customer id: log in again once the app has a current token.
            SallaPushLoginFailure.TOKEN_REJECTED ->
                telemetry.report("sallapush", "login_token_rejected", "Login refused", emptyMap())
            // The customer id or the token is malformed: report it.
            SallaPushLoginFailure.INVALID_REQUEST ->
                telemetry.report("sallapush", "login_invalid_request", "Login refused", emptyMap())
        }
    }
})
```

| Failure | What to do |
|---|---|
| `TOKEN_REJECTED` | Salla refused the token for this customer id: it expired, was revoked or belongs to another customer, or it couldn't be checked. The device keeps the link it had. Call `login` again when your app next refreshes the token or at the next launch, not from `onLoginFailure`. Pass the token without a `Bearer ` prefix. |
| `INVALID_REQUEST` | The login was refused as invalid. Check the customer id and the token you pass; if they are right, contact the Salla team with your app id. |

## Tags and language

The [README](../README.md#tags-and-language) shows `addTag` and `addTags`. To remove tags:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/UserSnippets.kt#remove-tags -->
```kotlin
SallaPush.User.removeTags(listOf("total_cart_amount", "cart_items"))
```

- **Keys** are trimmed and lowercased, then must be 1 to 64 characters of `a-z`, `0-9` and `_`; others are dropped
  with a warning. **Values** longer than 255 characters are cut, and an empty value removes the tag. A device holds at
  most 100 tags.
- **`getTags()`** returns the tags with your latest changes. Right after a cold start it may not have the saved tags
  yet, so don't use it to decide whether to set a tag.
- **A logout deletes the device's tags,** and so does a login for another customer, unless you
  `setClearTagsOnLogout(false)`. Set a customer's tags after their `login`.
- **`SallaPush.User.setLanguage(languageCode)`:** pass your app's language, such as `ar` or `en-US`, whenever it
  changes. Null means the device language.

## Push subscription

`SallaPush.User.pushSubscription` is the device's subscription. The sample's opt-in button:

<!-- snippet:sample/src/main/java/com/salla/push/sample/MainActivity.kt#opt-in-out -->
```kotlin
val subscription = SallaPush.User.pushSubscription
if (subscription.optedIn) subscription.optOut() else subscription.optIn()
```

- **`id`** is the SallaPush device id, **`token`** the device's push token, and **`optedIn`** is true when
  notifications are allowed, the user didn't opt out, and the device has a token. Right after a cold start they may be
  null or false until SallaPush has read its saved state; the observer then reports them.
- **`optOut()`** stops SallaPush notifications on this device, which stays registered. **`optIn()`** undoes it, and
  shows the permission prompt while it can still show.
- **`addObserver`** reports changes of the id, the token and the opt-in state, on the main thread. One added while the
  device is registered gets one call right away.

## Notifications

### Permission

On Android 13 and later the user must allow notifications. SallaPush never asks on its own.

- **`requestPermission(fallbackToSettings, callback)`** shows the system prompt. The callback runs once, on the main
  thread, with whether notifications are allowed. When they already are, or below Android 13, it answers at once.
- **`fallbackToSettings = true`** opens your app's notification settings when Android will no longer show the prompt.
- **`permission`** says whether notifications are allowed, **`canRequestPermission()`** whether the prompt can still
  show, and **`addPermissionObserver`** reports changes, on the main thread.
- **Your own prompt works too.** SallaPush reads the permission again whenever an activity of your app resumes; after
  any other change, call `refreshPermission()`.

From coroutines, `requestPermissionSuspend` is the suspending form:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/NotificationSnippets.kt#permission-suspend -->
```kotlin
suspend fun askForNotifications(): Boolean =
    SallaPush.Notifications.requestPermissionSuspend(fallbackToSettings = true)
```

### Taps

Add a click listener in `Application.onCreate`, after `initWithContext`, so that it also receives the tap that
started the app:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ApplicationSnippets.kt#click-listener -->
```kotlin
SallaPush.Notifications.addClickListener { event ->
    open(event.notification)
}
```

- **On the main thread,** with the notification in `event.notification` and its launch URL in `event.result.url`.
- **Taps before a listener exists** are kept, up to 10 for 5 minutes, and delivered to the first listener added.
- **A tap opens your app's launcher activity,** then calls your listeners. Route from the click listener, not from the
  launch intent's extras.
- **Remove listeners with their screen.** SallaPush keeps every listener and observer until you remove it
  (`removeClickListener`, `removeObserver` and the others), so remove those an Activity adds in its `onDestroy`.

#### Opening a screen from the listener

A listener added in `Application.onCreate` starts screens from the Application, which has no task of its own: add
`FLAG_ACTIVITY_NEW_TASK`, or Android refuses to start the screen, and SallaPush logs `api.callback_threw`. A router
that `open` ([Reading notification data](#reading-notification-data)) can call:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ShopRouter.kt#router -->
```kotlin
class ShopRouter(private val application: Application) : Router {

    override fun showProduct(id: Long) = show(Intent(application, ProductActivity::class.java).putExtra("id", id))

    override fun showOrder(id: String) = show(Intent(application, OrderActivity::class.java).putExtra("id", id))

    override fun openUrl(url: String) = show(Intent(application, WebActivity::class.java).putExtra("url", url))

    private fun show(intent: Intent) {
        // The Application has no task of its own: this flag opens the screen in your app's task, on top.
        application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
```

### Reading notification data

Route taps with the typed getters, never with raw payload keys, as the
[README](../README.md#notification-taps-and-deep-links) shows. `getString`, `getLong` and `getBoolean` read a value
whether it was sent as a string or as a JSON value. The keys (`type` and `id` there) are your own data, chosen by
whoever sends the notification. A `SallaPushNotification` also has:

| Property | What it holds |
|---|---|
| `title`, `body`, `subtitle` | The notification's text |
| `imageUrl` | The picture's https address |
| `launchUrl` | The URL the notification opens |
| `additionalData` | Your custom data |
| `notificationId` | The SallaPush message id |
| `androidNotificationId` | The id SallaPush posted the notification with, for `removeNotification` |
| `collapseId`, `channelId`, `sound` | As sent |
| `rawPayload` | The whole FCM data map |

### Launch URLs

SallaPush never opens a notification's URL on its own: it is in `event.result.url` and `notification.launchUrl`, for
your router. With `setOpenLaunchUrls(true)`, a tap opens an https launch URL in the app that handles it, such as the
browser. Your click listener still receives that tap, so don't open the URL again.

### Foreground notifications

While the app is in the foreground, foreground lifecycle listeners run on the main thread before SallaPush shows a
notification, and can hold it back, as the [README](../README.md#foreground-notifications) shows.

- **`preventDefault()`** keeps the notification from showing now. **`display()`** shows it later, once, within 10
  minutes.
- **Return quickly:** the listeners have at most 500 ms. A listener that hasn't returned by then can't stop the
  notification.

### Channels, colour and sound

The sample also sets these optional keys:

<!-- snippet:sample/src/main/AndroidManifest.xml#notification-style -->
```xml
<meta-data
    android:name="com.google.firebase.messaging.default_notification_color"
    android:resource="@color/notification_accent" />
<meta-data
    android:name="com.salla.push.default_channel_id"
    android:value="store_notifications" />
<meta-data
    android:name="com.salla.push.default_channel_name"
    android:resource="@string/notification_channel_name" />
```

- **Channel.** SallaPush posts to `com.salla.push.default_channel_id`, which it creates with high importance when
  missing. Without that key, it posts to Firebase's `com.google.firebase.messaging.default_notification_channel_id`
  when that channel exists, else to its own `sallapush_default` channel. A notification may name a channel your app
  created. A channel SallaPush creates is named by `com.salla.push.default_channel_name`, else "Notifications".
- **Colour:** Firebase's `com.google.firebase.messaging.default_notification_color`. **Sound:** the channel's.
- **Pictures.** An https picture of at most 5 MB that downloads within 8 seconds shows large. Otherwise the
  notification shows as text, and `notif.image_unusable` says why.

### Clearing

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/NotificationSnippets.kt#clear-notifications -->
```kotlin
SallaPush.Notifications.removeNotification(notification.androidNotificationId)
SallaPush.Notifications.clearAllNotifications()
```

Both remove only SallaPush's notifications: your own and other SDKs' stay.

## Badges

Android puts the badge on the app icon from the notifications in the shade: launchers count an app's notifications, so
an app can't set the number. Each SallaPush notification adds one, and it goes away when the user opens it or swipes
it away. Pixel phones show a dot; Samsung and Xiaomi phones show a number when the user picks numbers.

- **`autoClearBadge`, off by default:** the badge follows SallaPush's notifications in the shade. With
  `setAutoClearBadge(true)`, SallaPush removes its notifications that show on the badge from the shade when the app
  comes to the foreground; unlocking the phone with the app already open isn't an opening. (The iOS SDK clears its
  badge when the app opens, by default.)
- **`autoUpdateBadge`, on by default:** SallaPush's notification channels show badges. `setAutoUpdateBadge(false)`
  creates them without, so its notifications add nothing to the badge. Android keeps the badge setting of a channel
  that already exists, and `badge.channel_badges_on` says when a notification goes to one that shows badges: then
  create your own channel with `setShowBadge(false)` and name it in `com.salla.push.default_channel_id`.
- **To clear the badge at another moment,** such as a logout, call `clearAllNotifications()`.

The [README](../README.md#badges) shows the configuration that clears the badge when the app opens.

## Consent

If your app needs the user's consent before any data processing, require it at every launch, before
`initWithContext`, as the [README](../README.md#consent) shows, and record the user's choice whenever they make it:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ConsentSnippets.kt#consent-given -->
```kotlin
fun privacyChoiceChanged(allowsNotifications: Boolean) {
    SallaPush.setConsentGiven(allowsNotifications)
}
```

- **Require consent at every launch,** with `setConsentRequired(true)` or the configuration's
  `setRequiresUserConsent(true)`: neither is saved. The user's answer is saved.
- **While consent is required and not given,** SallaPush sends nothing and saves nothing but the answer. Your calls
  wait, and run in order once consent is given. SallaPush notifications that arrive are still shown.
- **`setConsentGiven(false)`** withdraws consent: SallaPush opts the device out, deletes its tags and unlinks it, then
  waits until consent is given again. The answer counts only while consent is required.

## Java

Java makes the same calls. The namespaces are getters (`SallaPush.getUser()`, `getNotifications()`, `getDebug()`), the
configuration is a builder, and every listener is an interface with one method to implement, so a lambda works. In
your `Application.onCreate`:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ShopApplication.java#java-application -->
```java
SallaPushConfig config = new SallaPushConfig.Builder()
        .setLogLevel(SallaPushLogLevel.INFO)
        .build();
SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002", config);
```

Elsewhere:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ShopApplication.java#java-calls -->
```java
SallaPush.getUser().addTag("add_to_cart", String.valueOf(System.currentTimeMillis()));
SallaPush.getUser().addObserver(state -> showCustomer(state.getCurrent().isLoggedIn()));
SallaPush.getNotifications().addClickListener(event -> open(event.getNotification()));
SallaPush.getNotifications().requestPermission(true, granted -> showPermission(granted));
```

An app's own FCM service, in Java:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/MyFirebaseMessagingService.java#java-messaging-service -->
```java
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        // First line: SallaPush shows its own messages and returns true; everything else is false.
        if (SallaPush.getNotifications().handleFirebaseMessage(getApplicationContext(), message)) {
            return;
        }
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    @Override
    public void onNewToken(String token) {
        SallaPush.getNotifications().onNewToken(getApplicationContext(), token);
        // Give the token to your other SDKs here.
    }
}
```

- **`SallaPush.login(customerId, identityToken)` and `SallaPush.logout()`** are static methods: Java calls them as
  they are.
- **Nulls are safe.** A `null` where SallaPush expects an object is logged (`api.null_argument`) and ignored. A `null`
  configuration means the defaults.
- **`SallaPushUserObserver.onLoginFailure`** has a default implementation, so a Java lambda implements the observer.

## Your FCM service

- **`handleFirebaseMessage(context, message)`** shows a SallaPush message and returns true. It returns false at once
  for every other message, which your service then hands to your other SDKs. On FCM's thread it returns once the
  notification is shown, which takes up to 8 seconds more for a picture.
- **`onNewToken(context, token)`** hands SallaPush a new FCM token. The same token twice costs nothing.
- **`isSallaPushMessage(message)`** says whether a message is SallaPush's, without handling it.
- **Priority.** FCM delivers each message to the `MESSAGING_EVENT` service with the highest `android:priority` (0 when
  unset), and SallaPush's own service has a negative one. When a library declares a service at 0 or above, give your
  service's `<intent-filter>` `android:priority="1"`.
- **A service that extends SallaPush's.** Instead of forwarding, your service can extend
  `SallaPushFirebaseMessagingService`, and call `super` first:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ShopMessagingService.kt#subclass-service -->
```kotlin
class ShopMessagingService : SallaPushFirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (SallaPush.Notifications.isSallaPushMessage(message)) return
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Give the token to your other SDKs here.
    }
}
```

To keep SallaPush's own service out of your merged manifest, add this inside `<application>`, with
`xmlns:tools="http://schemas.android.com/tools"` on the `<manifest>` element. It is optional: while your own service
exists, it changes nothing.

<!-- snippet:consumer-check/app/src/main/AndroidManifest.xml#remove-sdk-service -->
```xml
<service
    android:name="com.salla.push.SallaPushFirebaseMessagingService"
    tools:node="remove" />
```

## WorkManager

SallaPush uses WorkManager to send what is pending while your app isn't running. It never initialises WorkManager
itself, so your own `Configuration.Provider` and a `HiltWorkerFactory` work as they are. In an app that doesn't use
WorkManager yet, WorkManager's initializer then runs at every app start.

### Keeping WorkManager off your app's start

To have WorkManager start the first time it is used instead:

1. Take WorkManager's initializer out of your merged manifest, inside `<application>`, with
   `xmlns:tools="http://schemas.android.com/tools"` on the `<manifest>` element:

   <!-- snippet:consumer-check/app/src/main/AndroidManifest.xml#work-manager-on-demand -->
   ```xml
   <provider
       android:name="androidx.startup.InitializationProvider"
       android:authorities="${applicationId}.androidx-startup"
       android:exported="false"
       tools:node="merge">
       <meta-data
           android:name="androidx.work.WorkManagerInitializer"
           android:value="androidx.startup"
           tools:node="remove" />
   </provider>
   ```

2. Depend on WorkManager yourself, at SallaPush's version or a newer one:

   <!-- snippet:consumer-check/app/build.gradle.kts#work-dependency -->
   ```kotlin
   dependencies {
       implementation("androidx.work:work-runtime:2.9.1")
   }
   ```

3. Let your `Application` provide WorkManager's configuration: declare it as
   `class ShopApp : Application(), Configuration.Provider`, with this member:

   <!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/App.kt#work-manager-provider -->
   ```kotlin
   override val workManagerConfiguration: Configuration
       get() = Configuration.Builder().build()
   ```

Without step 3 WorkManager can't start at all: SallaPush logs `work.unavailable` once, and retries only while the app
runs.

## Privacy

- **For Google Play's Data safety form,** SallaPush collects device or other IDs (the push token and SallaPush's
  device id), user IDs (the customer id your app passes to `login`), app interactions (the notification events), and
  whatever you put in tags. All of it is collected for app functionality, linked to the user, and encrypted in transit.
- **SallaPush stores its data in `noBackupFilesDir`,** which Android never backs up or moves to another device, and
  uninstalling the app removes it. It never stores the identity token.
- **Its manifest** declares the permissions `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS`. Its consumer
  rules keep what R8 needs: add no ProGuard rule of your own.
- **Don't forward** `SallaPush.User.pushSubscription.token` to analytics or third parties.
