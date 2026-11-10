package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays every order of the socket events around one joiner on the real [Room]: messages read one at a time, sockets
 * cut with messages still on the way, the host noticing a dead socket early or late, and the game starting at any
 * point. However it goes, the joiner must end in one seat, connected, on the newest view.
 */
class SocketOrderTest {
    /** One run. A step is a plain string, so a failure prints the exact order that broke. */
    private class World(private var cutsLeft: Int) {
        private val h = Harness(FakeGame())
        private val unread = ArrayDeque<HostMsg?>() // on the way to the joiner's socket; null = the host closed it
        private val unnoticed = linkedSetOf<String>() // dead sockets the host has not noticed yet
        private var socket = -1
        private var live = false
        private var welcomed = false
        private var token: String? = null
        private var shownSeq = 0
        private var newestSeq = 0
        private var refused: HostMsg.Refused? = null
        private val conn get() = "c$socket"

        init {
            play("retry")
        }

        fun steps(): List<String> = buildList {
            if (refused != null) return@buildList
            if (!live) add("retry")
            if (live && unread.isNotEmpty()) add("read")
            if (live && cutsLeft > 0) add("cut")
            if (live && welcomed && h.room.phase == Phase.LOBBY && !h.room.hostLobby.seats[1].ready) add("ready")
            if (h.room.canStart) add("start")
            unnoticed.forEach { add("notice $it") }
        }

        fun play(step: String) {
            when {
                step == "retry" -> retry()
                step == "read" -> read()
                step == "cut" -> {
                    cutsLeft--
                    goDown()
                }
                step == "ready" -> send(h.room.onMessage(conn, ClientMsg.Ready(true)))
                step == "start" -> send(h.room.start(seed = 1))
                else -> step.removePrefix("notice ").let {
                    unnoticed -= it
                    send(h.room.onDisconnect(it))
                }
            }
        }

        // As NearbyClient does: the room code and join id until the first Welcome, then the token alone.
        private fun retry() {
            socket++
            live = true
            send(h.hello(conn, name = "Asha", token = token, secret = Harness.SECRET.takeIf { token == null }, joinId = "J".takeIf { token == null }))
        }

        private fun read() {
            when (val msg = unread.removeFirst()) {
                null -> goDown()
                is HostMsg.Welcome -> {
                    token = msg.reconnectToken
                    welcomed = true
                }
                is HostMsg.View -> if (msg.seq > shownSeq) shownSeq = msg.seq
                is HostMsg.Refused -> refused = msg
                else -> Unit
            }
        }

        private fun goDown() {
            live = false
            welcomed = false
            unread.clear()
            unnoticed += conn
        }

        private fun send(out: List<RoomOutput>) = out.forEach {
            when (it) {
                is RoomOutput.Send -> {
                    (it.msg as? HostMsg.View)?.let { v -> newestSeq = v.seq }
                    if (live && it.conn == conn) unread.addLast(it.msg)
                }
                is RoomOutput.Close -> if (live && it.conn == conn) unread.addLast(null) else if (it.conn != conn) unnoticed += it.conn
            }
        }

        fun assertSettled(order: List<String>) {
            val at = "order: $order"
            assertEquals(null, refused, at)
            assertEquals(listOf("Host", "Asha"), h.room.hostLobby.seats.map { it.name }, at)
            assertTrue(h.room.hostLobby.seats[1].connected, "the seat is not connected. $at")
            assertEquals(Phase.PLAYING, h.room.phase, at)
            assertTrue(shownSeq > 0, "the joiner never got a view. $at")
            assertEquals(newestSeq, shownSeq, at)
        }
    }

    private fun everyOrder(cuts: Int): Int {
        var orders = 0
        fun explore(path: List<String>) {
            val world = World(cuts).apply { path.forEach(::play) }
            val next = world.steps()
            if (next.isEmpty()) {
                orders++
                world.assertSettled(path)
            }
            next.forEach { explore(path + it) }
        }
        explore(emptyList())
        return orders
    }

    @Test
    fun givenOneCutSocket_whenEventsLandInAnyOrder_thenOneSeatAndTheNewestView() {
        assertTrue(everyOrder(cuts = 1) >= 90)
    }

    @Test
    fun givenTwoCutSockets_whenEventsLandInAnyOrder_thenOneSeatAndTheNewestView() {
        assertTrue(everyOrder(cuts = 2) >= 4000)
    }
}
