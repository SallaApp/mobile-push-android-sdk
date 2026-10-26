# SallaPush Android SDK: troubleshooting

Common problems and their fixes. [LOGGING.md](LOGGING.md#log-code-reference) lists every log code.

Back to the [README](../README.md).

| You see | Cause | Fix |
|---|---|---|
| Gradle: `Could not find com.salla:mobile-push` | `mavenCentral()` is missing from your repositories, or from those your projects declare themselves, in a build file or from a plugin; or that version isn't published | Add `mavenCentral()` as the [README](../README.md#installation) shows, and check the version. |
| `token.firebase_missing` | The app has no default Firebase app | Add `google-services.json` and the Google services plugin ([Add Firebase](../README.md#1-add-firebase)). |
| `token.missing` | No FCM token reached SallaPush 30 seconds after the app came to the foreground | The message names the likely cause: the Firebase setup, Firebase's auto-init, or a service that doesn't forward `onNewToken`. |
| `fcm.forwarding_hint`, or `register.ok` but no SallaPush notification shows | Another `FirebaseMessagingService` in your merged manifest gets the messages, or yours doesn't forward them | Forward them to SallaPush first ([Forward FCM messages to SallaPush](../README.md#3-forward-fcm-messages-to-sallapush)). With your own service forwarding, the hint is expected. |
| `display.default_icon` | No usable small icon in the meta-data; the message says why | Add a white-on-transparent drawable ([Set the notification icon](../README.md#2-set-the-notification-icon-required)). |
| `register.dormant` | The app id isn't accepted | Check your app id. SallaPush tries again after 24 hours. |
| `login.rejected`, `TOKEN_REJECTED` | The identity token was refused for that customer id: it expired, was revoked, belongs to another customer, or has a `Bearer ` prefix | Pass the customer's current token, without a prefix, with the id of its customer. Log in again with the next token or at the next launch ([Login failures](REFERENCE.md#login-failures)). |
| `identity.client_error`, `INVALID_REQUEST` | The login was refused as invalid | Check the customer id and the token you pass; if they are right, contact the Salla team with your app id. |
| `identity.token_needed` | SallaPush has to link the device again, and no `login` came in this launch | Call `login` right after `initWithContext` at every launch while signed in. |
| `notif.suppressed` | Notifications are off, the channel is blocked, or the user opted out; the message says which | Ask for the permission ([Permission](REFERENCE.md#permission)), let the user turn the channel back on, or call `optIn()`. |
| `permission.prompt_deferred`, and `optIn()` showed no prompt | `optIn()` ran before `initWithContext`, or while the app wasn't in the foreground | Nothing: the prompt shows at the next foreground. To ask at a moment you choose, call `requestPermission` from a screen. |
| `notif.image_unusable` | The picture couldn't be used: the download failed or took more than 8 seconds, or the picture is larger than 5 MB | The notification showed as text. Serve https pictures of at most 5 MB. |
| A tap opens the app, but not the screen it is about | Your click listener threw, which `api.callback_threw` reports: a screen started from the Application needs `FLAG_ACTIVITY_NEW_TASK`. Or your app routes from the launch intent's extras | Start screens with `FLAG_ACTIVITY_NEW_TASK`, and route from the click listener ([Opening a screen from the listener](REFERENCE.md#opening-a-screen-from-the-listener)). |
| `api.callback_threw` | One of your callbacks threw: an observer, a listener, the permission callback or a log listener. The message says which, and where | Fix that callback. SallaPush caught the exception, and the other callbacks still ran. |
| `work.unavailable` | WorkManager's initializer was removed, and the app provides no `Configuration.Provider` | Provide one ([Keeping WorkManager off your app's start](REFERENCE.md#keeping-workmanager-off-your-apps-start)), or keep the initializer. |
| The badge stays when the app opens | `autoClearBadge` is off, the default on Android: the badge follows SallaPush's notifications in the shade | To remove them when the app opens, `setAutoClearBadge(true)` ([Badges](REFERENCE.md#badges)). |
| `badge.channel_badges_on`, or SallaPush notifications count on the badge with `setAutoUpdateBadge(false)` | The channel already existed with badges, and Android lets only the user change it | Create your own channel with `setShowBadge(false)`, and name it in `com.salla.push.default_channel_id`. |
| `internal.error` | SallaPush caught an unexpected error of its own, skipped that step and kept running | Nothing in your app. Open an issue with the line and the SDK version. |
