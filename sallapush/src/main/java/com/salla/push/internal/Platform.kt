package com.salla.push.internal

import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.salla.push.BuildConfig
import com.salla.push.internal.api.HttpUrlConnectionTransport
import com.salla.push.internal.api.NetExecutor
import com.salla.push.internal.api.Network
import com.salla.push.internal.device.AndroidDeviceInfo
import com.salla.push.internal.device.DeviceInfoSource
import com.salla.push.internal.display.ImageLoader
import com.salla.push.internal.display.NotificationDisplayer
import com.salla.push.internal.display.SmallIcon
import com.salla.push.internal.lifecycle.AndroidConnectivityMonitor
import com.salla.push.internal.lifecycle.AndroidDeviceSettingsMonitor
import com.salla.push.internal.lifecycle.AndroidProcessInfo
import com.salla.push.internal.lifecycle.AppLifecycle
import com.salla.push.internal.lifecycle.ConnectivityMonitor
import com.salla.push.internal.lifecycle.DeviceSettingsMonitor
import com.salla.push.internal.lifecycle.ProcessInfo
import com.salla.push.internal.permission.NotificationPermission
import com.salla.push.internal.storage.GlobalStore
import com.salla.push.internal.storage.KeystoreSecretBox
import com.salla.push.internal.storage.SdkFiles
import com.salla.push.internal.storage.SecretBox
import com.salla.push.internal.util.printableAscii
import com.salla.push.internal.work.WorkManagerScheduler
import com.salla.push.internal.work.WorkScheduler
import java.io.File
import java.util.concurrent.Executor

/** The outcome of reading the FCM token. */
internal sealed class FirebaseToken {
    class Token(val value: String) : FirebaseToken()

    object FirebaseMissing : FirebaseToken()

    object AutoInitDisabled : FirebaseToken()

    class Failed(val reason: String) : FirebaseToken()
}

/** Reads the default FirebaseApp's FCM token; never initialises Firebase and never deletes tokens. */
internal fun interface FirebaseTokenSource {
    fun fetch(onResult: (FirebaseToken) -> Unit)
}

internal class FirebaseTokens(private val context: Context, private val executor: Executor) : FirebaseTokenSource {

    override fun fetch(onResult: (FirebaseToken) -> Unit) {
        try {
            // Only the default app (google-services.json) issues the app's token: another SDK's own named app, such as
            // the one OneSignal 5 creates, does not count.
            if (FirebaseApp.getApps(context).none { app -> runCatching { app.name }.getOrNull() == FirebaseApp.DEFAULT_APP_NAME }) {
                onResult(FirebaseToken.FirebaseMissing)
                return
            }
            val messaging = try {
                FirebaseMessaging.getInstance()
            } catch (e: IllegalStateException) {
                // The default app went away meanwhile (deleted by the app): Firebase is not set up for SallaPush.
                onResult(FirebaseToken.FirebaseMissing)
                return
            }
            if (!messaging.isAutoInitEnabled) {
                onResult(FirebaseToken.AutoInitDisabled)
                return
            }
            messaging.token.addOnCompleteListener(executor) { task ->
                val token = try {
                    if (task.isSuccessful) task.result else null
                } catch (e: Exception) {
                    null
                }
                if (token != null && token.isNotEmpty()) {
                    onResult(FirebaseToken.Token(token))
                } else {
                    onResult(FirebaseToken.Failed(task.exception?.javaClass?.simpleName ?: "no token"))
                }
            }
        } catch (e: Exception) {
            onResult(FirebaseToken.Failed(e.javaClass.simpleName))
        }
    }
}

/**
 * Everything bound to the app's Context. Building it does no I/O: storage directories, the keystore, the
 * PackageManager and Firebase are touched only later, on the engine thread or on demand.
 */
internal class CorePlatform(
    val context: Context,
    val files: SdkFiles,
    val secretBox: SecretBox,
    val network: Network,
    val device: DeviceInfoSource,
    val permission: () -> Boolean,
    val work: WorkScheduler,
    val connectivity: ConnectivityMonitor,
    val processInfo: ProcessInfo,
    val processIsForeground: () -> Boolean,
    val firebase: FirebaseTokenSource,
    val manifestReader: () -> ManifestConfig,
    val fcmServiceName: () -> String?,
    val displayer: NotificationDisplayer,
    val globalStore: GlobalStore,
    val deviceSettings: DeviceSettingsMonitor,
) {

    companion object {

        private const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"

        fun production(context: Context, env: CoreEnv, appVersionOverride: () -> String?): CorePlatform {
            val files = SdkFiles { File(context.noBackupFilesDir, "sallapush") }
            val userAgent = printableAscii(
                "SallaPush-Android/${BuildConfig.SDK_VERSION} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})",
            )
            return CorePlatform(
                context = context,
                files = files,
                secretBox = KeystoreSecretBox(),
                network = NetExecutor(HttpUrlConnectionTransport(userAgent), env.netRunner, env.engineRunner),
                device = AndroidDeviceInfo(context, appVersionOverride),
                permission = { NotificationPermission.allowed(context) },
                work = WorkManagerScheduler(context, env.logger),
                connectivity = AndroidConnectivityMonitor(context, env.logger),
                processInfo = AndroidProcessInfo(context),
                processIsForeground = { AppLifecycle.processIsForeground() },
                firebase = FirebaseTokens(context, Executor { env.engineRunner.execute(it) }),
                manifestReader = { ManifestConfig.read(context) },
                fcmServiceName = { resolveMessagingService(context) },
                displayer = NotificationDisplayer(env.logger, ImageLoader(), SmallIcon(env.logger)),
                globalStore = GlobalStore(files),
                deviceSettings = AndroidDeviceSettingsMonitor(context),
            )
        }

        /** The service FCM delivers messages to: the highest-priority MESSAGING_EVENT service of this package. */
        private fun resolveMessagingService(context: Context): String? {
            return try {
                val intent = Intent(MESSAGING_EVENT).setPackage(context.packageName)
                val manager = context.packageManager
                val resolved = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    manager.resolveService(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    manager.resolveService(intent, 0)
                }
                resolved?.serviceInfo?.name
            } catch (e: Exception) {
                null
            }
        }
    }
}
