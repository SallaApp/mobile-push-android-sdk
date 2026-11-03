# SallaPush Android SDK

Push notifications for the Android apps of Salla stores, from Kotlin and Java.

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fcom%2Fsalla%2Fmobile-push%2Fmaven-metadata.xml&label=Maven%20Central&color=004956)](https://central.sonatype.com/artifact/com.salla/mobile-push) [![License: MIT](https://img.shields.io/github/license/SallaApp/mobile-push-android-sdk?color=004956)](LICENSE) [![CI](https://img.shields.io/github/actions/workflow/status/SallaApp/mobile-push-android-sdk/ci.yml?branch=master&label=CI)](https://github.com/SallaApp/mobile-push-android-sdk/actions/workflows/ci.yml)

[Quick start](#quick-start) · [API reference](docs/REFERENCE.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) ·
[Log codes](docs/LOGGING.md) · [Example app](sample) · [Changelog](CHANGELOG.md)

> **SallaPush is for the mobile apps of Salla stores.** Contact the Salla team for your app id.

- **Version:** 1.1.0-rc.1. See [CHANGELOG.md](CHANGELOG.md).
- **Also for:** [iOS](https://github.com/SallaApp/mobile-push-ios-sdk) ·
  [Flutter](https://github.com/SallaApp/mobile-push-sdk/tree/master/flutter) ·
  [React Native](https://github.com/SallaApp/mobile-push-sdk/tree/master/react-native) ·
  [Capacitor](https://github.com/SallaApp/mobile-push-sdk/tree/master/capacitor)

**Contents:** [Requirements](#requirements) · [Installation](#installation) · [Platform setup](#platform-setup) ·
[Quick start](#quick-start) · [Usage](#usage) · [Other push SDKs](#sallapush-and-other-push-sdks) ·
[Example app](#example-app) · [Troubleshooting](#troubleshooting) · [Support](#support) ·
[Contributing](#contributing) · [License](#license)

## Requirements

| | Minimum |
|---|---|
| Android | 6.0 (API 23) |
| compileSdk | 34 |
| Language | Kotlin 1.9, or Java |
| Push | Firebase Cloud Messaging, on devices with Google Play services |

SallaPush brings `com.google.firebase:firebase-messaging:24.1.0`, `androidx.core:core:1.13.1`,
`androidx.work:work-runtime:2.9.1` and `org.jetbrains.kotlin:kotlin-stdlib:1.9.24`. A newer version in your app wins.

## Installation

SallaPush is on Maven Central. In `settings.gradle.kts`, declare Google's repository and Maven Central, as new Android
projects already do:

<!-- snippet:consumer-check/readme-repositories/kotlin/settings.gradle.kts#repositories mavenLocal=consumer-check/settings.gradle.kts#repositories -->
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Then add the dependency to your app module's `build.gradle.kts`:

<!-- snippet:consumer-check/app/build.gradle.kts#dependency -->
```kotlin
dependencies {
    implementation("com.salla:mobile-push:1.1.0-rc.1")
}
```

- **Groovy:** the repository block reads the same in `settings.gradle`, and the dependency is
  `implementation 'com.salla:mobile-push:1.1.0-rc.1'` in the app module's `build.gradle`.
- **Version catalog:** declare `sallapush = { module = "com.salla:mobile-push", version = "1.1.0-rc.1" }` under
  `[libraries]` in `gradle/libs.versions.toml`, and write `implementation(libs.sallapush)`.
- **Projects with repositories of their own,** declared in a build file or added by a plugin as in React Native apps:
  add `mavenCentral()` there too.
- **Updating:** change the exact version you pin, and read its entry in [CHANGELOG.md](CHANGELOG.md).

## Platform setup

### 1. Add Firebase

SallaPush's notifications arrive through Firebase Cloud Messaging. Add Firebase to your app as
[Firebase's guide](https://firebase.google.com/docs/android/setup) shows: your app's `google-services.json` in the app
module, and the Google services Gradle plugin. Leave Firebase's auto-init on (the default).

### 2. Set the notification icon (required)

Inside `<application>` in your `AndroidManifest.xml`:

<!-- snippet:sample/src/main/AndroidManifest.xml#small-icon -->
```xml
<meta-data
    android:name="com.salla.push.default_notification_icon"
    android:resource="@drawable/ic_stat_notification" />
```

The icon must be white on transparent: Android draws only its shape. Without it, SallaPush uses Firebase's
`com.google.firebase.messaging.default_notification_icon` when your app sets one, and otherwise shows a bell and logs
`display.default_icon`. [Channels, colour and sound](docs/REFERENCE.md#channels-colour-and-sound) has the optional
keys.

### 3. Forward FCM messages to SallaPush

Android gives each FCM message to one `FirebaseMessagingService`. If your app has none, SallaPush's own service gets
them: skip this step. If your app or another library in it declares one, declare your own service and forward to
SallaPush first, as the sample does:

<!-- snippet:sample/src/main/java/com/salla/push/sample/SampleMessagingService.kt -->
```kotlin
package com.salla.push.sample

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.salla.push.SallaPush

class SampleMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // First line: SallaPush shows its own messages and returns true; everything else is false.
        if (SallaPush.Notifications.handleFirebaseMessage(applicationContext, message)) return
        // Not a SallaPush message: hand it to your other SDKs here.
    }

    override fun onNewToken(token: String) {
        SallaPush.Notifications.onNewToken(applicationContext, token)
        // Give the token to your other SDKs here.
    }
}
```

<!-- snippet:sample/src/main/AndroidManifest.xml#messaging-service -->
```xml
<service
    android:name=".SampleMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

When a library declares a messaging service too, give your `<intent-filter>` `android:priority="1"`, so that FCM
delivers to yours ([Your FCM service](docs/REFERENCE.md#your-fcm-service)).

## Quick start

### Initialize

Start SallaPush in your `Application` class's `onCreate`, with your app id, and name that class in the manifest's
`android:name`. The sample's `Application` starts with:

<!-- snippet:sample/src/main/java/com/salla/push/sample/SampleApp.kt#initialize -->
```kotlin
SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002")
// Added here, the click listener also receives the tap that started the app.
SallaPush.Notifications.addClickListener { event -> SampleRouter.open(this, event) }
```

`initWithContext` returns at once; calls made before it are kept until it runs. `SampleRouter` stands for your app's
navigation ([Notification taps and deep links](#notification-taps-and-deep-links)).

### Ask for notification permission

On Android 13 and later the user must allow notifications. SallaPush never asks on its own: ask where it fits your
app, as the sample does from a button:

<!-- snippet:sample/src/main/java/com/salla/push/sample/MainActivity.kt#request-permission -->
```kotlin
SallaPush.Notifications.requestPermission(fallbackToSettings = true) { granted ->
    showPermissionAnswer(granted)
}
```

The callback runs once, on the main thread; from coroutines, call `requestPermissionSuspend(fallbackToSettings)`. Java
makes the same calls through getters such as `SallaPush.getNotifications()` ([Java](docs/REFERENCE.md#java)).

**Verify it works.** In a debug build, set `SallaPush.Debug.logLevel = SallaPushLogLevel.VERBOSE` before
`initWithContext`, or the `com.salla.push.LOG_LEVEL` meta-data to `verbose`. Run the app on a device, or an emulator
image with Google APIs, allow notifications, and filter Logcat with `adb logcat -s SallaPush`: expect `init.started`,
then `token.received` and `register.ok` at the first launch, and `login.ok` once a customer is logged in;
`SallaPush.Notifications.permission` is `true` once notifications are allowed.

## Usage

### Login and logout

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/UserSnippets.kt#login-logout -->
```kotlin
// After the customer signs in:
SallaPush.login(customerId, identityToken)

// After the customer signs out:
SallaPush.logout()
```

Call `login` at each launch too, while the customer is signed in ([details](docs/REFERENCE.md#login-and-logout)).

### Notification permission

`SallaPush.Notifications.permission` says whether notifications are allowed, `canRequestPermission()` whether the
prompt can still show, and `addPermissionObserver` reports changes ([Permission](docs/REFERENCE.md#permission)).

### Notification taps and deep links

The quick start's click listener gets every tap, on the main thread, with the notification in `event.notification`.
Route it with the typed getters:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ApplicationSnippets.kt#route -->
```kotlin
private fun open(notification: SallaPushNotification) {
    when (notification.getString("type")) {
        "product" -> notification.getLong("id")?.let { router.showProduct(it) }
        "order" -> notification.getString("id")?.let { router.showOrder(it) }
        else -> notification.launchUrl?.let { router.openUrl(it) }
    }
}
```

- Start screens from the `Application` with `Intent.FLAG_ACTIVITY_NEW_TASK`
  ([Opening a screen from the listener](docs/REFERENCE.md#opening-a-screen-from-the-listener)).
- `notification.launchUrl` holds the notification's URL. With `SallaPushConfig.Builder().setOpenLaunchUrls(true)`, a
  tap opens an https URL in the browser or the app that handles it; your listener still gets the tap, so don't open
  the URL again.

### Listeners and observers

Pass a lambda to every listener and observer, except `SallaPushUserObserver`: in Kotlin, implement it with
`object : SallaPushUserObserver { … }`. SallaPush keeps each one until you remove it, so remove those of a screen with
it, as the sample's screen does in `onDestroy`:

<!-- snippet:sample/src/main/java/com/salla/push/sample/MainActivity.kt#remove-observers -->
```kotlin
SallaPush.User.removeObserver(userObserver)
SallaPush.User.pushSubscription.removeObserver(subscriptionObserver)
SallaPush.Notifications.removePermissionObserver(permissionObserver)
SallaPush.Notifications.removeForegroundLifecycleListener(foregroundListener)
SallaPush.Debug.removeLogListener(logListener)
```

### Foreground notifications

While the app is in the foreground, foreground listeners run before SallaPush shows a notification and can hold it back:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/NotificationSnippets.kt#foreground-listener -->
```kotlin
SallaPush.Notifications.addForegroundLifecycleListener { event ->
    if (checkout.isInProgress) {
        event.preventDefault()
        checkout.whenFinished { event.notification.display() }
    }
}
```

Return quickly: the listeners have at most 500 ms. `display()` shows the notification once, within 10 minutes.

### Tags and language

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/UserSnippets.kt#tags -->
```kotlin
SallaPush.User.addTag("add_to_cart", System.currentTimeMillis().toString())
SallaPush.User.addTags(mapOf("total_cart_amount" to total.toString(), "cart_items" to items.toString()))
```

- Keys are lowercased: use `a-z`, `0-9` and `_`, up to 64 characters. Values are cut at 255 characters, and a device
  holds at most 100 tags. `removeTag` and `removeTags` remove them; `getTags()` returns them.
- A logout clears the tags; `SallaPushConfig.Builder().setClearTagsOnLogout(false)` keeps them. Set a customer's tags
  after their `login`.
- Pass your app's language with `SallaPush.User.setLanguage(languageCode)`, such as `ar`, whenever it changes.

### Push subscription

`SallaPush.User.pushSubscription` holds the device id, the push token and the opt-in state, and its observer reports
their changes. `optOut()` stops SallaPush notifications on the device; `optIn()` turns them back on, and shows the
permission prompt while it can still show.

### Consent

If your app needs the user's consent first, require it at every launch, before `initWithContext`:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ApplicationSnippets.kt#consent-required -->
```kotlin
SallaPush.setConsentRequired(true)
SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002")
```

Then call `SallaPush.setConsentGiven(true)` when the user agrees: until then, SallaPush sends nothing. `false`
withdraws consent: SallaPush opts the device out, deletes its tags and unlinks the customer
([Consent](docs/REFERENCE.md#consent)).

### Logging

SallaPush logs to Logcat under the tag `SallaPush`, at `WARN` by default. To send errors to your own telemetry:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/LoggingSnippets.kt#log-listener -->
```kotlin
val telemetryListener = SallaPushLogListener { event ->
    telemetry.report("sallapush", event.code, event.message, event.metadata)
}
SallaPush.Debug.addLogListener(telemetryListener, SallaPushLogLevel.ERROR)
```

Every entry has a stable `code`: match on it, not on the message ([Log codes](docs/LOGGING.md#log-code-reference)).

### Badges

Android launchers count an app's notifications in the shade for the icon's badge, as a dot or a number.

- **`autoClearBadge`, off by default:** the badge follows SallaPush's notifications in the shade. Turned on, SallaPush
  removes its notifications from the shade when the app comes to the foreground. (The iOS SDK clears by default.)
- **`autoUpdateBadge`, on by default:** SallaPush's notification channels show badges. `setAutoUpdateBadge(false)`
  creates them without, so its notifications add nothing to the badge.

To clear the badge when the app opens:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/ApplicationSnippets.kt#badges -->
```kotlin
val config = SallaPushConfig.Builder()
    .setAutoClearBadge(true) // remove SallaPush's notifications from the shade when the app opens
    .build()
SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002", config)
```

To clear it at another moment, call `clearAllNotifications()` ([Badges](docs/REFERENCE.md#badges)).

## SallaPush and other push SDKs

SallaPush takes over nothing in your app: it shows and reports only its own notifications, and
`handleFirebaseMessage` returns `false` for every other message, so your other push SDKs keep working. In a
`FirebaseMessagingService` that serves several SDKs, call SallaPush first, as in
[step 3](#3-forward-fcm-messages-to-sallapush).

## Example app

[sample](sample) builds without any secret: an `Application` that starts SallaPush as the quick start does and logs the
signed-in customer in at each launch, the forwarding service, and a screen to sign in, ask for the permission, opt out
and in, set a tag, and follow SallaPush's log lines and taps. Open the repository in Android Studio and run `sample`. To
receive notifications, put your app id in `SampleApp.kt` and your Firebase values in `~/.gradle/gradle.properties`, as
[CONTRIBUTING.md](CONTRIBUTING.md#the-sample-app) shows.

## Troubleshooting

| Problem or log code | Fix |
|---|---|
| Gradle: `Could not find com.salla:mobile-push` | Add `mavenCentral()` to your repositories ([Installation](#installation)), and check the version. |
| `token.firebase_missing` or `token.missing` | Add Firebase ([step 1](#1-add-firebase)), and forward `onNewToken` ([step 3](#3-forward-fcm-messages-to-sallapush)). |
| `fcm.forwarding_hint`, or no notification shows | Forward FCM messages to SallaPush first ([step 3](#3-forward-fcm-messages-to-sallapush)). |
| `register.dormant` | Check your app id. |
| `display.default_icon` | Add a white-on-transparent small icon ([step 2](#2-set-the-notification-icon-required)). |
| The badge stays when the app opens | Expected by default on Android. To remove SallaPush's notifications when the app opens, `setAutoClearBadge(true)` ([Badges](#badges)). |

[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) has more. When you ask for help, include the SDK version, the
Android version and device, and the SallaPush log codes at verbose level.

## Support

- **SDK bugs and feature requests:** [open an
  issue](https://github.com/SallaApp/mobile-push-android-sdk/issues/new/choose) with the SDK and Android versions and
  the SallaPush log codes you saw. Never paste tokens, keys or customer data.
- **App ids:** Contact the Salla team for your app id.

## Contributing

Pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first. Everyone taking part follows the
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

The SallaPush Android SDK is available under the [MIT License](LICENSE).
