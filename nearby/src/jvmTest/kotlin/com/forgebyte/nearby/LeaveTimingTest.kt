package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** ceo, 2026-11-03: one full-suite run spent 267 s in NearbyHost.leave(). Leave with joiners connected must be quick. */
class LeaveTimingTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private suspend fun oneRoom(timeline: MutableList<String>): Duration {
        val joinLines = Collections.synchronizedList(mutableListOf<String>())
        val j0 = TimeSource.Monotonic.markNow()
        traceSink = { joinLines += "${j0.elapsedNow().inWholeMilliseconds} $it" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = NearbyHost(FakeGame(), Harness.config(turnTimeoutMs = 2_000, botDelayMs = 100), scope)
        try {
            val port = host.open()
            val clients = (1..2).map {
                NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope).also { c ->
                    c.join(JoinCode("127.0.0.1", port, Harness.SECRET, host.fingerprint!!), "P$it")
                    val s = withTimeoutOrNull(15.seconds) { c.status.first { s -> s == ClientStatus.Connected || s is ClientStatus.Ended } }
                    check(s == ClientStatus.Connected) { "P$it ended as $s; timeline $joinLines" }
                    c.ready(true)
                }
            }
            fun state() = "lobby ${host.lobby.value.seats.map { "${it.name}:${it.kind}:${it.connected}:${it.ready}" }}, " +
                "clients ${clients.map { it.status.value }}, timeline $joinLines"
            withTimeoutOrNull(10.seconds) { host.canStart.first { it } } ?: error("never startable: ${state()}")
            host.startGame(seed = 3)
            clients.forEachIndexed { i, c ->
                withTimeoutOrNull(10.seconds) { c.view.first { v -> v != null } } ?: error("P${i + 1} got no view: ${state()}")
            }
            val start = TimeSource.Monotonic.markNow()
            traceSink = { timeline += "${start.elapsedNow().inWholeMilliseconds} $it" }
            host.leave()
            return start.elapsedNow()
        } finally {
            traceSink = null
            scope.cancel()
        }
    }

    @Test
    fun givenJoinersMidGame_whenHostLeaves_thenLeaveIsQuickEveryTime() = runBlocking<Unit> {
        var worst = Duration.ZERO
        var worstTimeline = emptyList<String>()
        repeat(20) {
            val timeline = Collections.synchronizedList(mutableListOf<String>())
            val took = withTimeout(30.seconds) { oneRoom(timeline) }
            if (took > worst) { worst = took; worstTimeline = timeline.toList() }
        }
        println("worst leave $worst: $worstTimeline")
        assertTrue(worst < 3.seconds, "worst leave $worst: $worstTimeline")
    }
}
