package com.forgebyte.nearby.demo

import com.forgebyte.nearby.ClientStatus
import com.forgebyte.nearby.InMemorySeatStore
import com.forgebyte.nearby.JoinCode
import com.forgebyte.nearby.NearbyClient
import com.forgebyte.nearby.Phase
import com.forgebyte.nearby.SeatStore
import com.forgebyte.nearby.ShortCode
import com.forgebyte.nearby.discoverRooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Joins a demo room (by [code], by a typed [short] code, back into the seat [seatStore] kept when [resume] is set, or
 * else the first room found nearby), plays every own turn
 * after [thinkMs], and reports through [say]. [onClient] gets the client before it connects (the Android sample uses it
 * to leave on command and to draw its status line). Shared by the Mac client and the Android sample, so both joiners
 * behave the same.
 * Returns true when the match reached the end.
 */
public suspend fun playAsJoiner(
    code: JoinCode?, name: String, thinkMs: Long, short: ShortCode? = null,
    seatStore: SeatStore = InMemorySeatStore(), resume: Boolean = false,
    onClient: (NearbyClient<DemoMove, DemoView>) -> Unit = {}, say: (String) -> Unit,
): Boolean {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = NearbyClient(DemoGame.ID, DemoGame.VERSION, DemoMove.serializer(), DemoView.serializer(), scope, seatStore = seatStore)
    onClient(client)
    val start = TimeSource.Monotonic.markNow()
    try {
        if (resume) {
            val saved = seatStore.load() ?: return false.also { say("FAIL no saved seat to resume") }
            say("resuming seat in ${saved.host}:${saved.port} as ${saved.name}")
            client.resume(saved)
        } else if (code != null) {
            say("joining with code $code")
            client.join(code, name)
        } else if (short != null) {
            say("asking to join with short code $short")
            client.requestJoin(short, name)
        } else {
            say("looking for rooms on the local network")
            val room = withTimeoutOrNull(30.seconds) {
                discoverRooms(DemoGame.ID).mapNotNull { rooms -> rooms.firstOrNull { it.canJoin(DemoGame.VERSION) } }.first()
            } ?: return false.also { say("FAIL no room found in 30 s") }
            say("found '${room.roomName}' at ${room.host}:${room.port} after ${start.elapsedNow()}; asking to join")
            client.requestJoin(room, name)
        }
        val joined = withTimeoutOrNull(90.seconds) {
            client.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended }
        }
        if (joined != ClientStatus.Connected) return false.also { say("FAIL join ended as $joined") }
        say("joined as seat ${client.lobby.value!!.yourSeat} after ${start.elapsedNow()}")

        val rtts = mutableListOf<Duration>()
        var sentAt: TimeSource.Monotonic.ValueTimeMark? = null
        scope.launch { client.status.collect { say("status $it") } }
        scope.launch { client.checkCode.collect { if (it != null) say("check code $it (the host sees the same)") } }
        scope.launch { client.turnTimer.collect { t -> if (t != null) say("turn clock: seat ${t.seat}, ${t.remaining.inWholeMilliseconds} ms left") } }
        scope.launch { client.rejections.collect { say("move refused: $it") } }
        scope.launch {
            combine(client.seqView.filterNotNull(), client.status) { sv, s -> sv to s }.collect { (sv, s) ->
                val v = sv.view
                sentAt?.let { rtts += it.elapsedNow(); sentAt = null } // first view after our move answers it
                if (s == ClientStatus.Connected && v.turn == client.lobby.value!!.yourSeat && v.hand.isNotEmpty()) {
                    delay(thinkMs)
                    sentAt = TimeSource.Monotonic.markNow()
                    // The seq of the view this move was picked on: if the turn clock played first, the host says "Too late".
                    if (!client.move(DemoMove(v.hand.first()), seenSeq = sv.seq)) sentAt = null
                }
            }
        }
        client.ready(true)
        val finish = withTimeoutOrNull(600.seconds) {
            merge(
                client.lobby.filter { it?.phase == Phase.OVER }.map { "over" },
                client.status.filterIsInstance<ClientStatus.Ended>().map { "ended: ${it.reason}" },
            ).first()
        }
        val sorted = rtts.sorted()
        fun pct(p: Int) = sorted.getOrNull((sorted.size * p / 100).coerceAtMost(sorted.size - 1))
        say("moves timed ${sorted.size}; round trip p50 ${pct(50)}, p95 ${pct(95)}, max ${sorted.lastOrNull()}")
        if (finish != "over") return false.also { say("FAIL ${finish ?: "match did not end in 600 s"}") }
        say("PASS match over after ${start.elapsedNow()}; my hand ${client.view.value?.hand?.size}, pile ${client.view.value?.pile?.size}")
        client.leave()
        return true
    } finally {
        scope.cancel()
    }
}
