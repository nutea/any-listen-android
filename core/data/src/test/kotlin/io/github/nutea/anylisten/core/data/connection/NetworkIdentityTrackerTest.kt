package io.github.nutea.anylisten.core.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ConnectivityManager` repeats callbacks, overlaps the old and new default network during a
 * handover, and recycles net ids. These cases pin down what the session state machine is allowed
 * to see.
 */
class NetworkIdentityTrackerTest {
    private val wifi = Any()
    private val cellular = Any()

    @Test
    fun startsUnknownSoAFreshSessionIsNotTreatedAsAHandover() {
        assertEquals(NetworkSnapshot.Unknown, NetworkIdentityTracker().snapshot())
        assertFalse(NetworkIdentityTracker().snapshot().known)
    }

    @Test
    fun repeatedCallbacksForTheSameNetworkKeepOneIdentity() {
        val tracker = NetworkIdentityTracker()
        val first = tracker.onValidated(wifi)
        repeat(4) { assertEquals(first, tracker.onValidated(wifi)) }
        assertTrue(first.online)
    }

    @Test
    fun adifferentNetworkGetsADifferentIdentity() {
        val tracker = NetworkIdentityTracker()
        val onWifi = tracker.onValidated(wifi)
        val onCellular = tracker.onValidated(cellular)
        assertNotEquals(onWifi.id, onCellular.id)
        assertTrue(onCellular.online)
    }

    @Test
    fun losingValidationReportsOfflineWithoutForgettingTheLink() {
        val tracker = NetworkIdentityTracker()
        val online = tracker.onValidated(wifi)
        assertFalse(tracker.onUnvalidated(wifi).online)
        // The same link coming back keeps its identity, so a live socket is probed, not replaced.
        assertEquals(online, tracker.onValidated(wifi))
    }

    @Test
    fun aLostNetworkRetiresItsIdentitySoRecycledNetIdsAreNotMistakenForIt() {
        val tracker = NetworkIdentityTracker()
        val before = tracker.onValidated(wifi)
        assertFalse(tracker.onLost(wifi).online)
        val after = tracker.onValidated(wifi)
        assertNotEquals(before.id, after.id)
    }

    @Test
    fun losingANetworkThatIsNotTheDefaultDoesNotTakeUsOffline() {
        val tracker = NetworkIdentityTracker()
        tracker.onValidated(wifi)
        val current = tracker.onValidated(cellular)
        assertEquals(current, tracker.onLost(wifi))
        assertTrue(tracker.snapshot().online)
    }

    @Test
    fun aHandoverReportsTheNewLinkEvenWhenTheOldLossArrivesLate() {
        val tracker = NetworkIdentityTracker()
        val onWifi = tracker.onValidated(wifi)
        val onCellular = tracker.onValidated(cellular)
        val afterLateLoss = tracker.onLost(wifi)
        assertNotEquals(onWifi.id, onCellular.id)
        assertEquals(onCellular, afterLateLoss)
    }

    @Test
    fun identityCacheStaysBounded() {
        val tracker = NetworkIdentityTracker(capacity = 4)
        repeat(64) { tracker.onValidated(Any()) }
        assertTrue(tracker.snapshot().online)
    }
}
