package com.forgebyte.nearby.macclient

import com.forgebyte.nearby.ClientStatus
import com.forgebyte.nearby.JoinCode
import com.forgebyte.nearby.NearbyClient
import com.forgebyte.nearby.Phase
import com.forgebyte.nearby.demo.DemoGame
import com.forgebyte.nearby.demo.DemoMove
import com.forgebyte.nearby.demo.DemoView
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Exit code 0 when the match reached the end, 1 otherwise. */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val think = opts["--think"]?.toLongOrNull() ?: 0L // ms before each move, to make a match long enough for a Wi-Fi toggle
    val ok = runBlocking { run(opts["--code"]?.let(JoinCode::parse), opts["--name"] ?: "Mac", think) }
    exitProcess(if (ok) 0 else 1)
}

private fun say(text: String) = println("[nearby] $text")

private suspend fun run(code: JoinCode?, name: String, thinkMs: Long): Boolean {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = NearbyClient(DemoGame.ID, DemoGame.VERSION, DemoMove.serializer(), DemoView.serializer(), scope)
    val start = TimeSource.Monotonic.markNow()
    try {
        if (code != null) {
            say("joining with code $code")
            client.join(code, name)
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
        scope.launch {
            combine(client.view.filterNotNull(), client.status) { v, s -> v to s }.collect { (v, s) ->
                sentAt?.let { rtts += it.elapsedNow(); sentAt = null } // first view after our move answers it
                if (s == ClientStatus.Connected && v.turn == client.lobby.value!!.yourSeat && v.hand.isNotEmpty()) {
                    delay(thinkMs)
                    sentAt = TimeSource.Monotonic.markNow()
                    if (!client.move(DemoMove(v.hand.first()))) sentAt = null
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
