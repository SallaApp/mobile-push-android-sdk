# SallaPush Android SDK: logging

Log levels, forwarding entries to your telemetry, what is never logged,
and every log code SallaPush writes.

Back to the [README](../README.md).

## Contents

1. [Logging](#logging)
2. [Log code reference](#log-code-reference)

## Logging

SallaPush logs to Logcat under the tag `SallaPush`, one line per entry: its code, then its message.

```text
I/SallaPush: token.received: Push token received (…x7Kq2Z).
```

- **Levels:** `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG` and `VERBOSE`, from silent to most detailed. The default is
  `WARN`. Logcat shows an entry when its level is not `OFF` and the entry is at that level or a more severe one.
- **Release builds log too:** messages are redacted before they are logged.
- **Precedence:** `SallaPush.Debug.logLevel` at run time, then the configuration's `setLogLevel`, then the meta-data
  `com.salla.push.LOG_LEVEL`, then `WARN`. A meta-data value that is not a level is reported once, as
  `api.invalid_argument`. The sample sets the meta-data:

<!-- snippet:sample/src/main/AndroidManifest.xml#log-level -->
```xml
<meta-data
    android:name="com.salla.push.LOG_LEVEL"
    android:value="verbose" />
```

At run time:

<!-- snippet:consumer-check/app/src/main/java/com/salla/push/consumercheck/readme/LoggingSnippets.kt#runtime-log-level -->
```kotlin
SallaPush.Debug.logLevel = SallaPushLogLevel.VERBOSE
```

### Forward entries to your telemetry

The [README](../README.md#logging) shows a listener that forwards errors to your telemetry.

- **Its own level.** A listener at `ERROR` receives errors, one at `WARN` (the level of `addLogListener(listener)`)
  warnings and errors, and so on, whatever the Logcat level: `Debug.logLevel = OFF` silences only Logcat.
  `removeLogListener` removes a listener.
- **Off the main thread.** Listeners run on a private thread, behind a queue of 500 entries. When they fall behind, the
  oldest entries go, and one `log.dropped` entry says how many. A listener must return quickly. One that throws is
  skipped for that entry, and the first such throw in a launch is logged as ERROR `api.callback_threw`.
- **From when it is added.** A listener receives the entries logged after it was added. The first listener of the
  process also receives, before them, the warnings and errors logged before any listener existed that its level
  admits, at most 100: a problem found during `initWithContext`, such as an invalid app id, reaches a listener added
  right after it.
- **An event** has a `level`, a `category` (`lifecycle`, `registration`, `identity`, `notifications`, `events`, `tags`,
  `network`, `storage` or `consent`), a stable `code`, a one-line `message`, `timestampMillis`, and `metadata` whose
  keys are only `http_status`, `error_code`, `request_id`, `device_id`, `app_id`, `attempt`, `delay_s`, `route`,
  `reason` and `count`. Codes never change: match on them, not on messages. The [log code
  reference](#log-code-reference) lists them all.

### What is never logged

The identity token, the device's credentials, the full push token (only its last 6 characters), any customer
identifier, request bodies and notification payload values. Anything that looks like a secret is masked. An app
callback that throws is reported (`api.callback_threw`) with where it threw, never with its message.

## Log code reference

Every code SallaPush logs on Android, with its level. Codes are stable across releases.

<!-- log-codes:start -->
| Code | Level | Meaning |
|---|---|---|
| `init.started` | info | SallaPush started, with the app id and the log level; a second `initWithContext` with the same app id says it changes nothing |
| `init.invalid_app_id` | error | The app id is invalid: in the process's first `initWithContext`, SallaPush stays off; in a later one, the call is ignored and SallaPush keeps the previous app id |
| `init.app_id_changed` | warn | `initWithContext` switched to another app id; the previous one finishes its pending logout |
| `init.drain_started` | info | Finishing another app id's pending logout from an earlier session |
| `init.buffer_full` | warn | Too many calls or notification events before SallaPush could take them; the oldest tag, language and opt-in calls, or events, were dropped, and the message says how many |
| `init.not_main_process` | debug | Not the app's main process: SallaPush only shows notifications here and ignores its other calls |
| `consent.waiting` | info | Waiting for `setConsentGiven(true)` |
| `consent.given` | info | Consent given; SallaPush starts |
| `consent.revoked` | info | Consent was withdrawn; the message says which steps were applied, and SallaPush is idle |
| `api.invalid_argument` | warn | Something was ignored, and the message says what and why: a call with an invalid argument, such as an empty tag key, or a `login` whose external id or identity token is empty or too long; `requestPermission` before `initWithContext` or while the app isn't in the foreground; the first call after an invalid app id turned SallaPush off; or a `com.salla.push.LOG_LEVEL` value that isn't a level |
| `api.null_argument` | error | A `null` was passed where SallaPush expects an object; the call was ignored |
| `api.callback_threw` | error | An app callback threw: an observer, a click or foreground lifecycle listener, the permission callback, or a log listener (once per launch). SallaPush caught it and went on, and the others still ran; the message says which callback and where it threw |
| `token.received` | info | The FCM token reached SallaPush |
| `token.unchanged` | debug | The same token again |
| `token.invalid` | warn | The token passed to SallaPush is empty or malformed |
| `token.missing` | warn | No token 30 seconds after the app came to the foreground; the message names the likely cause |
| `token.firebase_missing` | warn | The app has no default Firebase app (`google-services.json`); SallaPush waits for `onNewToken` |
| `token.auto_init_disabled` | debug | Firebase auto-init is off; SallaPush waits for `onNewToken` |
| `token.fetch_failed` | warn | Reading the FCM token failed; SallaPush waits for `onNewToken` |
| `register.ok` | info | The device is registered |
| `register.reregistering` | info | Registering again, with the reason |
| `register.dormant` | error | The app id isn't accepted: check it. SallaPush tries again in 24 hours |
| `update.sent` | debug | The device record was updated; the message names the fields that changed |
| `heartbeat.sent` | debug | The daily device refresh was sent |
| `permission.changed` | info | The notification permission changed |
| `permission.prompt_deferred` | info | `optIn()` was called before `initWithContext` or while the app wasn't in the foreground: the permission prompt shows at the next foreground, unless the permission is granted or permanently denied by then |
| `subscription.opted_out` | info | `optOut()` opted the device out; a call that changes nothing logs nothing |
| `subscription.opted_in` | info | `optIn()` cleared the opt-out; a call that changes nothing logs nothing |
| `login.queued` | debug | A login waits for the registration, or one for the same customer is already queued |
| `login.ok` | info | The customer is linked |
| `login.skipped_same` | debug | Already linked to this customer; nothing to send |
| `login.switched` | info | An account switch: a `login` for another customer while one is signed in; the message says what happens to the previous customer's tags |
| `login.rejected` | warn | The identity token was refused; the device isn't linked to the customer, and the login isn't sent again ([Login failures](REFERENCE.md#login-failures)) |
| `logout.queued` | info | A logout waits, with the reason, and is retried until it is done |
| `logout.ok` | info | The customer is unlinked |
| `logout.skipped` | debug | Not linked: the logout needs no request |
| `identity.token_needed` | warn | SallaPush has no identity token in memory for the signed-in customer, so the link waits for the app's next `login` call; said at most once per launch, while the app is in the foreground |
| `identity.client_error` | error | A login or a logout was refused: a login refused as invalid isn't sent again (`INVALID_REQUEST`); any other refusal is sent again after 24 hours |
| `tags.flushed` | debug | Tags were sent, or the device's tags were cleared at a logout or an account switch |
| `tags.normalized` | debug | A tag key was lowercased |
| `tags.invalid_key` | warn | A tag key is invalid and was dropped |
| `tags.value_truncated` | warn | A tag value was longer than 255 characters and was truncated |
| `tags.limit` | warn | More than 100 tags; the extra additions were dropped |
| `tags.rejected_key` | warn | A tag was refused; it was dropped and the others kept |
| `events.flushed` | debug | Events were sent |
| `events.dropped` | warn | Events older than 6.5 days were dropped |
| `events.queue_full` | warn | The event queue is full; the oldest events were dropped |
| `net.retry` | info | A request failed and will be retried, with the cause and the delay |
| `net.retry_exhausted` | warn | A request failed 6 times in this process; it waits for the next launch, foreground or network change |
| `net.rate_limited` | warn | SallaPush was asked to slow down |
| `net.client_error` | error | A request was refused |
| `net.redirect_refused` | error | An answer was a redirect, which SallaPush never follows |
| `net.invalid_response` | warn | An answer was unusable (the message says why); the request will be retried |
| `net.routes_closed` | warn | SallaPush can't send device updates for now; pending work is kept and sent later |
| `net.monitor_unavailable` | warn | SallaPush can't watch for the network coming back (no connectivity service, or Android refused the callback); a request that failed for lack of a network waits for its next retry, the next foreground or, for events, the next WorkManager run |
| `storage.failed` | error | SallaPush data couldn't be read or written (the message says which); a consent answer it couldn't read is read again at the next foreground, consent call or `initWithContext` |
| `storage.state_reset` | error | Saved state was corrupt and was reset; the message says which: the identity state (its pending logins and logouts are lost; the app's next `login` or `logout` sets it again), the rest of the state (pending logouts were kept), the pending events (dropped), or the consent answer (it counts as not given until the app sets it again) |
| `storage.crypto_reset` | warn | The saved credentials couldn't be decrypted (a Keystore failure); registering again |
| `notif.foreign` | verbose | A message of another SDK was ignored |
| `notif.parse_failed` | warn | A SallaPush notification was only partly readable, such as a message id that isn't SallaPush's or a picture address that isn't https; shown with what was readable |
| `notif.unknown_version` | warn | A notification uses a newer payload version; read with version 1 rules |
| `notif.suppressed` | debug | A notification wasn't shown: notifications off, channel blocked, opted out, or no text |
| `notif.display_failed` | warn | A notification couldn't be shown, or a `display()` showed nothing; the message says why |
| `notif.image_unusable` | warn | A notification's picture couldn't be used (the download failed or took more than 8 seconds, or the picture was larger than 5 MB, not an image, or too large to post); the notification shows as text, and the message says why |
| `notif.click_queued` | debug | A tap is kept until a click listener is added |
| `notif.click_delivered` | debug | A tap was delivered to the click listeners |
| `display.default_icon` | warn | No usable small icon in the meta-data; SallaPush's own icon is used, and the message says why |
| `badge.cleared` | debug | The app came to the foreground and SallaPush removed its notifications that show on the badge from the shade (`setAutoClearBadge(true)`); the message and `count` say how many |
| `badge.channel_badges_on` | warn | `setAutoUpdateBadge(false)`, but the channel a notification went to already shows badges, so the notification counts on the app icon: Android lets only the user change a channel that exists. Said once per channel and launch |
| `fcm.forwarding_hint` | info | FCM delivers to another service, yours or another SDK's, and nothing was forwarded yet; a reminder to check the forwarding |
| `work.unavailable` | warn | WorkManager isn't available; retries continue while the app runs |
| `log.dropped` | warn | A log listener fell behind and entries were dropped |
| `internal.error` | error | SallaPush caught an unexpected error of its own: the call or step that hit it was skipped, the message says where, and SallaPush keeps running |
<!-- log-codes:end -->
