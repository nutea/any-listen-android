package io.github.nutea.anylisten.core.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect rules, exercised as a table. Every case here corresponds to a way the old
 * multi-owner design could misbehave on a real device.
 */
class ConnectionPlannerTest {
    private val wifi = NetworkSnapshot(id = 7L, online = true)
    private val cellular = NetworkSnapshot(id = 8L, online = true)
    private val offline = NetworkSnapshot.Offline

    private fun connected(
        generation: Long = 1L,
        networkId: Long = wifi.id,
        network: NetworkSnapshot = wifi,
        linkDown: Boolean = false,
    ) = ConnectionModel(
        state = ConnectionState.Online(generation, networkId, linkDown),
        network = network,
        hasCredentials = true,
        lastGeneration = generation,
    )

    private fun reduce(model: ConnectionModel, event: ConnectionEvent, now: Long = 0L) =
        ConnectionPlanner.reduce(model, event, now, jitter = 0.0)

    @Test
    fun firstNetworkCallbackDuringConnectAdoptsInsteadOfRestarting() {
        val connecting = ConnectionModel(
            state = ConnectionState.Connecting(1, NetworkSnapshot.UNKNOWN),
            network = NetworkSnapshot.Unknown,
            hasCredentials = true,
            lastGeneration = 1L,
        )
        val step = reduce(connecting, ConnectionEvent.NetworkChanged(wifi))
        assertEquals(ConnectionAction.None, step.action)
        assertEquals(ConnectionState.Connecting(1, wifi.id), step.model.state)
        assertEquals(1L, step.model.lastGeneration)
    }

    @Test
    fun firstNetworkCallbackAdoptsAHealthySessionInsteadOfRebuildingIt() {
        // 0.1.1-beta.4: the socket is built before ConnectivityManager ever speaks, and its first
        // callback used to tear the fresh session down right after a successful login.
        val model = connected(networkId = NetworkSnapshot.UNKNOWN, network = NetworkSnapshot.Unknown)
        val step = reduce(model, ConnectionEvent.NetworkChanged(wifi))
        assertEquals(ConnectionAction.None, step.action)
        assertEquals(ConnectionState.Online(1L, wifi.id, linkDown = false), step.model.state)
    }

    @Test
    fun repeatedCallbacksForTheSameNetworkDoNothing() {
        var model = connected()
        repeat(5) {
            val step = reduce(model, ConnectionEvent.NetworkChanged(wifi))
            assertEquals(ConnectionAction.None, step.action)
            model = step.model
        }
        assertEquals(1L, model.lastGeneration)
    }

    @Test
    fun switchingToADifferentNetworkRebuildsTheSession() {
        val step = reduce(connected(), ConnectionEvent.NetworkChanged(cellular))
        val action = step.action as ConnectionAction.Connect
        assertEquals(2L, action.generation)
        assertEquals(1L, action.previousGeneration)
        assertEquals(cellular.id, action.networkId)
        assertEquals(ConnectionState.Connecting(1, cellular.id), step.model.state)
    }

    @Test
    fun losingTheLinkKeepsTheSocketAndOnlyMarksItSuspect() {
        val step = reduce(connected(), ConnectionEvent.NetworkChanged(offline))
        assertEquals(ConnectionAction.None, step.action)
        assertEquals(ConnectionState.Online(1L, wifi.id, linkDown = true), step.model.state)
    }

    @Test
    fun sameNetworkComingBackProbesTheSocketInsteadOfReplacingIt() {
        val down = reduce(connected(), ConnectionEvent.NetworkChanged(offline)).model
        val back = reduce(down, ConnectionEvent.NetworkChanged(wifi))
        assertEquals(ConnectionAction.Probe(1L), back.action)
        assertEquals(ConnectionState.Online(1L, wifi.id, linkDown = false), back.model.state)
    }

    @Test
    fun aFailedProbeIsReportedAsAClosedSocketAndSchedulesOneRetry() {
        val model = connected()
        val step = reduce(model, ConnectionEvent.SocketClosed(1L, ConnectionFault.STALE), now = 1_000L)
        val backoff = step.model.state as ConnectionState.Backoff
        assertEquals(1, backoff.attempt)
        assertEquals(ConnectionFault.STALE, backoff.fault)
        assertTrue(step.action is ConnectionAction.ScheduleRetry)
    }

    @Test
    fun aClosedSocketFromAReplacedGenerationIsIgnored() {
        // The crash shape from 0.1.1-beta.1: a dying socket tearing down its healthy successor.
        val model = connected(generation = 4L)
        val step = reduce(model, ConnectionEvent.SocketClosed(3L, ConnectionFault.TRANSPORT))
        assertEquals(ConnectionAction.None, step.action)
        assertEquals(model.state, step.model.state)
    }

    @Test
    fun aLateSuccessFromAReplacedGenerationIsDroppedNotAdopted() {
        val model = connected().let { reduce(it, ConnectionEvent.NetworkChanged(cellular)).model }
        val stale = reduce(model, ConnectionEvent.Established(1L))
        assertEquals(ConnectionAction.Drop(1L), stale.action)
        assertTrue(stale.model.state is ConnectionState.Connecting)
    }

    @Test
    fun connectRequestNeverDisturbsALiveOrHalfBuiltSession() {
        assertEquals(ConnectionAction.None, reduce(connected(), ConnectionEvent.ConnectRequested()).action)
        val connecting = ConnectionModel(
            state = ConnectionState.Connecting(1, wifi.id),
            network = wifi,
            hasCredentials = true,
            lastGeneration = 3L,
        )
        assertEquals(ConnectionAction.None, reduce(connecting, ConnectionEvent.ConnectRequested()).action)
    }

    @Test
    fun explicitSignInReplacesEvenAHealthySession() {
        val step = reduce(connected(), ConnectionEvent.ConnectRequested(force = true))
        val action = step.action as ConnectionAction.Connect
        assertEquals(2L, action.generation)
    }

    @Test
    fun connectRequestWithoutCredentialsStaysSignedOut() {
        val step = reduce(ConnectionModel(), ConnectionEvent.ConnectRequested())
        assertEquals(ConnectionState.SignedOut, step.model.state)
        assertEquals(ConnectionAction.None, step.action)
    }

    @Test
    fun connectRequestWithoutAUsableNetworkWaitsInsteadOfBurningAnAttempt() {
        val model = ConnectionModel(state = ConnectionState.Idle, network = offline, hasCredentials = true)
        val step = reduce(model, ConnectionEvent.ConnectRequested())
        assertEquals(ConnectionState.Offline, step.model.state)
        assertEquals(ConnectionAction.None, step.action)
    }

    @Test
    fun aUsableNetworkResumesFromOfflineAndFromBackoff() {
        val waiting = ConnectionModel(state = ConnectionState.Offline, network = offline, hasCredentials = true)
        assertTrue(reduce(waiting, ConnectionEvent.NetworkChanged(wifi)).action is ConnectionAction.Connect)

        val backoff = ConnectionModel(
            state = ConnectionState.Backoff(4, 9_000L, ConnectionFault.TRANSPORT),
            network = wifi,
            hasCredentials = true,
            lastGeneration = 2L,
        )
        val resumed = reduce(backoff, ConnectionEvent.NetworkChanged(cellular))
        assertTrue(resumed.action is ConnectionAction.Connect)
        // A brand new link resets the penalty; the user should not wait out an old backoff.
        assertEquals(ConnectionState.Connecting(1, cellular.id), resumed.model.state)
    }

    @Test
    fun rejectedCredentialsStopRetrying() {
        val connecting = ConnectionModel(
            state = ConnectionState.Connecting(1, wifi.id),
            network = wifi,
            hasCredentials = true,
            lastGeneration = 1L,
        )
        val step = reduce(connecting, ConnectionEvent.AttemptFailed(1L, ConnectionFault.REJECTED, "nope"))
        assertEquals(ConnectionState.Rejected("nope"), step.model.state)
        assertEquals(ConnectionAction.None, step.action)
    }

    @Test
    fun transportFailuresBackOffAndEachRetryWaitsLonger() {
        var model = ConnectionModel(
            state = ConnectionState.Connecting(1, wifi.id),
            network = wifi,
            hasCredentials = true,
            lastGeneration = 1L,
        )
        val delays = mutableListOf<Long>()
        repeat(4) {
            val failed = reduce(model, ConnectionEvent.AttemptFailed(model.lastGeneration, ConnectionFault.TRANSPORT))
            delays += (failed.action as ConnectionAction.ScheduleRetry).delayMs
            val retried = reduce(failed.model, ConnectionEvent.RetryElapsed)
            assertTrue(retried.action is ConnectionAction.Connect)
            model = retried.model
        }
        assertEquals(delays.sorted(), delays)
        assertNotEquals(delays.first(), delays.last())
        assertTrue(delays.all { it <= ConnectionBackoff.MAX_MS })
    }

    @Test
    fun backoffIsCappedAndAlwaysPositive() {
        assertEquals(ConnectionBackoff.BASE_MS / 2, ConnectionBackoff.delayMs(1, jitter = 0.0))
        assertTrue(ConnectionBackoff.delayMs(99, jitter = 0.99) <= ConnectionBackoff.MAX_MS)
        assertTrue(ConnectionBackoff.delayMs(99, jitter = 0.0) > 0)
    }

    @Test
    fun aRetryThatFiresWhileOfflineWaitsForTheNetwork() {
        val model = ConnectionModel(
            state = ConnectionState.Backoff(2, 0L, ConnectionFault.TRANSPORT),
            network = offline,
            hasCredentials = true,
            lastGeneration = 2L,
        )
        val step = reduce(model, ConnectionEvent.RetryElapsed)
        assertEquals(ConnectionState.Offline, step.model.state)
        assertEquals(ConnectionAction.None, step.action)
    }

    @Test
    fun signOutDropsTheLiveSocketAndForgetsCredentials() {
        val step = reduce(connected(generation = 5L), ConnectionEvent.SignOut)
        assertEquals(ConnectionAction.Drop(5L, logout = true), step.action)
        assertEquals(ConnectionState.SignedOut, step.model.state)
        assertTrue(!step.model.hasCredentials)
    }

    @Test
    fun losingCredentialsWhileConnectedSignsOut() {
        val step = reduce(connected(generation = 2L), ConnectionEvent.CredentialsChanged(present = false))
        assertEquals(ConnectionState.SignedOut, step.model.state)
        assertEquals(ConnectionAction.Drop(2L, logout = true), step.action)
    }

    @Test
    fun losingTheLinkMidAttemptWaitsRatherThanRetryingIntoNothing() {
        val connecting = ConnectionModel(
            state = ConnectionState.Connecting(1, wifi.id),
            network = wifi,
            hasCredentials = true,
            lastGeneration = 1L,
        )
        val step = reduce(connecting, ConnectionEvent.NetworkChanged(offline))
        assertEquals(ConnectionState.Offline, step.model.state)
    }

    @Test
    fun aHandoverDuringAnAttemptRestartsItOnTheNewLink() {
        val connecting = ConnectionModel(
            state = ConnectionState.Connecting(3, wifi.id),
            network = wifi,
            hasCredentials = true,
            lastGeneration = 1L,
        )
        val step = reduce(connecting, ConnectionEvent.NetworkChanged(cellular))
        val action = step.action as ConnectionAction.Connect
        assertEquals(2L, action.generation)
        assertEquals(ConnectionState.Connecting(1, cellular.id), step.model.state)
    }

    @Test
    fun establishingWhileTheLinkIsDownRecordsTheSuspicion() {
        val model = ConnectionModel(
            state = ConnectionState.Connecting(1, NetworkSnapshot.NONE),
            network = offline,
            hasCredentials = true,
            lastGeneration = 1L,
        )
        val step = reduce(model, ConnectionEvent.Established(1L))
        assertEquals(ConnectionState.Online(1L, NetworkSnapshot.NONE, linkDown = true), step.model.state)
    }
}
