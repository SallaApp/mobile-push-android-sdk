package com.salla.push.internal.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger

/** Reports when a network becomes available. Used only while an operation has failed with a network error. */
internal interface ConnectivityMonitor {
    fun start(onAvailable: () -> Unit)

    fun stop()
}

/**
 * One callback per process (API 24+: registerDefaultNetworkCallback; API 23: registerNetworkCallback), registered
 * only while needed. The network that is already current when the callback is registered is ignored, so only a real
 * change triggers retries. Every call is guarded. When no callback can be registered (no connectivity service, or a
 * refusal such as TooManyRequestsException on API 30+), WARN net.monitor_unavailable says so, once until a
 * registration succeeds: the failed requests keep their retry schedule, and the next foreground resets it anyway.
 */
internal class AndroidConnectivityMonitor(
    private val context: Context,
    private val logger: Logger,
) : ConnectivityMonitor {

    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** net.monitor_unavailable was logged, and no registration succeeded since. Guarded by [lock]. */
    private var unavailableLogged = false

    override fun start(onAvailable: () -> Unit) {
        synchronized(lock) {
            if (callback != null) return
            val manager = connectivityManager()
            if (manager == null) {
                reportUnavailable("no connectivity service")
                return
            }
            val initial: Network? = try {
                manager.activeNetwork
            } catch (e: Exception) {
                null
            }
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var skippedInitial = initial == null

                override fun onAvailable(network: Network) {
                    try {
                        if (!skippedInitial && network == initial) {
                            skippedInitial = true
                            return
                        }
                        skippedInitial = true
                        onAvailable()
                    } catch (t: Throwable) {
                        // Runs on the system's ConnectivityThread: a SallaPush failure must never surface there.
                    }
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    manager.registerDefaultNetworkCallback(networkCallback)
                } else {
                    val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                    manager.registerNetworkCallback(request, networkCallback)
                }
                callback = networkCallback
                unavailableLogged = false
            } catch (e: RuntimeException) {
                // No monitoring this time; the next network failure asks again.
                reportUnavailable(e.javaClass.simpleName)
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val registered = callback ?: return
            callback = null
            val manager = connectivityManager() ?: return
            try {
                manager.unregisterNetworkCallback(registered)
            } catch (e: RuntimeException) {
                // Already unregistered.
            }
        }
    }

    private fun connectivityManager(): ConnectivityManager? = try {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (e: RuntimeException) {
        null
    }

    private fun reportUnavailable(reason: String) {
        if (unavailableLogged) return
        unavailableLogged = true
        logger.log(LogCode.NET_MONITOR_UNAVAILABLE) {
            "SallaPush cannot watch for the network coming back ($reason); requests that failed for lack of a network " +
                "wait for their next retry, the next foreground or, for events, the next WorkManager run."
        }
    }
}
