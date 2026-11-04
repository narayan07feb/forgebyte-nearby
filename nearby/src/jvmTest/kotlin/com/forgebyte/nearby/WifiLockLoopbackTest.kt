package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * ceo, 2026-11-05: the Wi-Fi low-latency lock is held on both phones only while a match runs, and released on every
 * way a match ends: game over, host leaves, joiner leaves, joiner kicked, the game's scope cancelled.
 */
class WifiLockLoopbackTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostLock = FakeWifiLock()
    private val joinerLock = FakeWifiLock()
    private val host = NearbyHost(FakeGame(cardsEach = 1), Harness.config(), scope).apply { newWifiLock = { hostLock } }
    private lateinit var joiner: NearbyClient<FakeMove, FakeView>

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        withTimeoutOrNull(10.seconds) { host.leave() }
        scope.cancel()
    }

    /** Host and one joiner in a started match, both locks held. One card each: the host's first move ends it. */
    private suspend fun playing() {
        val port = host.open(bindHost = LOOPBACK)
        joiner = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)
        joiner.newWifiLock = { joinerLock }
        joiner.join(JoinCode(LOOPBACK, port, Harness.SECRET, host.fingerprint!!), "Asha")
        joiner.status.first { it == ClientStatus.Connected }
        joiner.ready(true)
        host.canStart.first { it }
        assertEquals(0, hostLock.acquires + joinerLock.acquires, "nothing taken in the lobby")
        host.startGame(seed = 1)
        hostLock.held.first { it }
        joinerLock.held.first { it }
    }

    private suspend fun bothReleased() {
        hostLock.held.first { !it }
        joinerLock.held.first { !it }
    }

    @Test
    fun givenMatchRunning_whenItIsOver_thenBothReleased() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            playing()
            host.move(FakeMove(host.seqView.first { it != null }!!.view.hand.single()))
            joiner.lobby.first { it?.phase == Phase.OVER }
            bothReleased()
        }
    }

    @Test
    fun givenMatchRunning_whenHostLeaves_thenBothReleased() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            playing()
            host.leave()
            joiner.status.first { it == ClientStatus.Ended(EndReason.HostLeft) }
            bothReleased()
        }
    }

    @Test
    fun givenMatchRunning_whenJoinerLeaves_thenJoinerReleased() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            playing()
            joiner.leave()
            joiner.status.first { it == ClientStatus.Ended(EndReason.Left) }
            joinerLock.held.first { !it }
        }
    }

    @Test
    fun givenMatchRunning_whenJoinerKicked_thenJoinerReleased() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            playing()
            host.kick(1)
            joiner.status.first { it == ClientStatus.Ended(EndReason.Kicked) }
            joinerLock.held.first { !it }
        }
    }

    @Test
    fun givenMatchRunning_whenScopeCancelled_thenBothReleased() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            playing()
            scope.cancel()
            bothReleased()
        }
    }
}
