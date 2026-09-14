package io.github.nutea.anylisten.core.data.connection

/**
 * Identity of the default network the process is currently allowed to use.
 *
 * [id] only changes when Android hands out a *different* default network, which is the single
 * signal that tells a live session apart from one that is still bound to a dead interface.
 * Repeated callbacks for the same link keep the same id, so a healthy session is never rebuilt
 * just because `ConnectivityManager` spoke again.
 */
data class NetworkSnapshot(val id: Long, val online: Boolean) {
    val known: Boolean get() = id != UNKNOWN

    companion object {
        /** No default network has been observed yet. */
        const val UNKNOWN = -1L

        /** A default network was observed, and right now there is none usable. */
        const val NONE = 0L

        val Unknown = NetworkSnapshot(UNKNOWN, online = false)
        val Offline = NetworkSnapshot(NONE, online = false)
    }
}

/**
 * Turns the duplicated, out-of-order `ConnectivityManager.NetworkCallback` stream into stable
 * network identities.
 *
 * Android delivers `onAvailable`, `onCapabilitiesChanged` and `onLost` on a binder thread, may
 * repeat them, and overlaps the new default network with the old one during a Wi-Fi/cellular
 * handover. Callers only ever see the reduced [NetworkSnapshot], so none of that ordering leaks
 * into the session state machine.
 *
 * Not thread safe: the owning monitor serialises callbacks before calling in.
 */
class NetworkIdentityTracker(private val capacity: Int = DEFAULT_CAPACITY) {
    private val ids = LinkedHashMap<Any, Long>()
    private var next = 1L
    private var current: Any? = null
    private var validated = false

    fun snapshot(): NetworkSnapshot {
        val handle = current ?: return if (ids.isEmpty() && next == 1L) NetworkSnapshot.Unknown else NetworkSnapshot.Offline
        if (!validated) return NetworkSnapshot.Offline
        return NetworkSnapshot(idFor(handle), online = true)
    }

    /** The default network can carry traffic. */
    fun onValidated(handle: Any): NetworkSnapshot {
        current = handle
        validated = true
        return snapshot()
    }

    /** The default network is still attached but cannot carry traffic (captive portal, no internet). */
    fun onUnvalidated(handle: Any): NetworkSnapshot {
        if (current == handle || current == null) {
            current = handle
            validated = false
        }
        return snapshot()
    }

    /**
     * The default network went away. Its id is retired so that a recycled Android net id can never
     * be mistaken for the link a live socket was opened on.
     */
    fun onLost(handle: Any): NetworkSnapshot {
        ids.remove(handle)
        if (current == handle) {
            current = null
            validated = false
        }
        return snapshot()
    }

    private fun idFor(handle: Any): Long = ids.getOrPut(handle) {
        if (ids.size >= capacity) {
            ids.keys.firstOrNull()?.let(ids::remove)
        }
        next++
    }

    private companion object {
        const val DEFAULT_CAPACITY = 32
    }
}
