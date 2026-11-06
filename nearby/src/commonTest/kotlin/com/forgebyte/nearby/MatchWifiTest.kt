package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records what the room asked of the platform's Wi-Fi lock. */
class FakeWifiLock : WifiLatencyLock {
    val held = MutableStateFlow(false)
    var acquires = 0
    override fun acquire() {
        acquires++
        held.value = true
    }
    override fun release() {
        held.value = false
    }
}

/** ceo, 2026-11-05: low-latency Wi-Fi only while a match runs, released on every end path. */
class MatchWifiTest {
    private val lock = FakeWifiLock()
    private var made = 0
    private val newLock = { made++; lock }

    @Test
    fun givenLobbyOnly_whenRoomEnds_thenNoLockIsEverMade() = runTest {
        holdWifiDuringMatch(flow { emit(Phase.LOBBY); emit(Phase.CLOSED) }, newLock)
        assertEquals(0, made)
    }

    @Test
    fun givenMatchStarts_whenItIsOver_thenHeldDuringAndReleasedAfter() = runTest {
        val phase = MutableStateFlow<Phase?>(Phase.LOBBY)
        val hold = launch(start = CoroutineStart.UNDISPATCHED) { holdWifiDuringMatch(phase, newLock) }
        assertFalse(lock.held.value)
        phase.value = Phase.PLAYING
        yield()
        assertTrue(lock.held.value, "held while playing")
        phase.value = Phase.OVER
        yield()
        assertFalse(lock.held.value, "released when the match is over, before the room closes")
        assertEquals(1, lock.acquires)
        hold.cancel()
    }

    @Test
    fun givenMatchRunning_whenCancelled_thenReleased() = runTest {
        val phase = MutableStateFlow<Phase?>(Phase.PLAYING)
        val hold = launch(start = CoroutineStart.UNDISPATCHED) { holdWifiDuringMatch(phase, newLock) }
        assertTrue(lock.held.value)
        hold.cancel()
        hold.join()
        assertFalse(lock.held.value, "leave, end of session or the game's scope going away")
    }

    @Test
    fun givenMatchRunning_whenPhasesFail_thenReleased() = runTest {
        assertFailsWith<IllegalStateException> {
            holdWifiDuringMatch(flow { emit(Phase.PLAYING); error("boom") }, newLock)
        }
        assertEquals(1, lock.acquires)
        assertFalse(lock.held.value)
    }
}
