package io.github.nutea.anylisten.core.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for "which default network are we on, and can it carry traffic". */
interface NetworkMonitor {
    val status: StateFlow<NetworkSnapshot>

    fun start()

    fun stop()
}

/**
 * One process-wide `registerDefaultNetworkCallback`.
 *
 * Previously the player service and the view model each registered their own callback and each
 * kept a private `observedDefaultNetwork` flag, so a single Wi-Fi/cellular handover produced two
 * independent reconnect decisions that raced. Everything now observes this one flow.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val appContext = context.applicationContext
    private val tracker = NetworkIdentityTracker()
    private val state = MutableStateFlow(NetworkSnapshot.Unknown)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override val status: StateFlow<NetworkSnapshot> = state.asStateFlow()

    override fun start() {
        if (callback != null) return
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                publish {
                    if (capabilities.usableForServerTraffic()) tracker.onValidated(network)
                    else tracker.onUnvalidated(network)
                }
            }

            override fun onLost(network: Network) = publish { tracker.onLost(network) }
        }
        callback = registered
        runCatching { manager.registerDefaultNetworkCallback(registered) }
            .onFailure { callback = null }
        publish { tracker.snapshot() }
    }

    override fun stop() {
        val registered = callback ?: return
        callback = null
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        runCatching { manager?.unregisterNetworkCallback(registered) }
    }

    /**
     * Callbacks arrive on a binder thread. Reducing under a lock keeps [NetworkIdentityTracker]
     * single threaded and guarantees the flow only ever moves forward.
     */
    private fun publish(reduce: () -> NetworkSnapshot) {
        val snapshot = synchronized(tracker) { reduce() }
        state.value = snapshot
    }
}

internal fun NetworkCapabilities.usableForServerTraffic(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
