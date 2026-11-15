package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays every order of the socket events around one or two joiners on the real [Room]: messages read one at a time,
 * sockets cut with messages still on the way, the host noticing a dead socket early or late, and the game starting at
 * any point. However it goes, each joiner must end in one seat, connected, on the newest view.
 */
class SocketOrderTest {
    /** One joiner as NearbyClient behaves: its sockets, what is on the way to it, and what it has shown. */
    private class Joiner(val name: String, var cutsLeft: Int) {
        var socket = -1
        var live = false
        var welcomed = false
        var token: String? = null
        var shownSeq = 0
        var newestSeq = 0
        var refused: HostMsg.Refused? = null
        val unread = ArrayDeque<HostMsg?>() // on the way to the current socket; null = the host closed it
        val conn get() = "$name$socket"

        fun owns(conn: String) = conn.trimEnd { it.isDigit() } == name
    }

    /** One run. A step is a plain string ("Asha read"), so a failure prints the exact order that broke. */
    private class World(names: List<String>, cutsEach: Int) {
        private val h = Harness(FakeGame(), Harness.config(minSeats = 1 + names.size)) // the host waits for everyone
        private val joiners = names.map { Joiner(it, cutsEach) }
        private val unnoticed = linkedSetOf<String>() // dead sockets the host has not noticed yet

        fun steps(): List<String> = buildList {
            joiners.filter { it.refused == null }.forEach { j ->
                val seat = h.room.hostLobby.seats.firstOrNull { it.name == j.name }
                if (!j.live) add("${j.name} retry")
                if (j.live && j.unread.isNotEmpty()) add("${j.name} read")
                if (j.live && j.cutsLeft > 0) add("${j.name} cut")
                if (j.live && j.welcomed && h.room.phase == Phase.LOBBY && seat?.ready == false) add("${j.name} ready")
            }
            if (h.room.canStart) add("start")
            unnoticed.forEach { add("notice $it") }
        }

        fun play(step: String) {
            if (step == "start") return send(h.room.start(seed = 1))
            if (step.startsWith("notice ")) return step.removePrefix("notice ").let {
                unnoticed -= it
                send(h.room.onDisconnect(it))
            }
            val (name, action) = step.split(" ")
            val j = joiners.single { it.name == name }
            when (action) {
                "retry" -> retry(j)
                "read" -> read(j)
                "cut" -> {
                    j.cutsLeft--
                    goDown(j)
                }
                "ready" -> send(h.room.onMessage(j.conn, ClientMsg.Ready(true)))
            }
        }

        // As NearbyClient does: the room code and join id until the first Welcome, then the token alone.
        private fun retry(j: Joiner) {
            j.socket++
            j.live = true
            val first = j.token == null
            send(h.hello(j.conn, name = j.name, token = j.token, secret = Harness.SECRET.takeIf { first }, joinId = "J${j.name}".takeIf { first }))
        }

        private fun read(j: Joiner) {
            when (val msg = j.unread.removeFirst()) {
                null -> goDown(j)
                is HostMsg.Welcome -> {
                    j.token = msg.reconnectToken
                    j.welcomed = true
                }
                is HostMsg.View -> if (msg.seq > j.shownSeq) j.shownSeq = msg.seq
                is HostMsg.Refused -> j.refused = msg
                else -> Unit
            }
        }

        private fun goDown(j: Joiner) {
            j.live = false
            j.welcomed = false
            j.unread.clear()
            unnoticed += j.conn
        }

        private fun send(out: List<RoomOutput>) = out.forEach { o ->
            val j = joiners.singleOrNull { it.owns(o.conn) } ?: return@forEach // the host's own screen
            val current = j.live && o.conn == j.conn
            when (o) {
                is RoomOutput.Send -> {
                    (o.msg as? HostMsg.View)?.let { v -> j.newestSeq = v.seq }
                    if (current) j.unread.addLast(o.msg)
                }
                is RoomOutput.Close -> if (current) j.unread.addLast(null) else if (o.conn != j.conn) unnoticed += o.conn
            }
        }

        private val RoomOutput.conn get() = when (this) {
            is RoomOutput.Send -> conn
            is RoomOutput.Close -> conn
        }

        /** What a run ended with, to compare the full and the reduced walk. */
        fun end() = h.room.hostLobby.seats.toString() + joiners.map { listOf(it.socket, it.token, it.shownSeq, it.newestSeq, it.live) } + unnoticed

        fun assertSettled(order: List<String>) {
            val at = "order: $order"
            val seats = h.room.hostLobby.seats
            assertEquals(listOf("Host") + joiners.map { it.name }.sorted(), listOf("Host") + seats.drop(1).map { it.name }.sorted(), at)
            assertEquals(Phase.PLAYING, h.room.phase, at)
            joiners.forEach { j ->
                assertEquals(null, j.refused, "${j.name} was refused. $at")
                assertTrue(seats.single { it.name == j.name }.connected, "${j.name}'s seat is not connected. $at")
                assertTrue(j.shownSeq > 0, "${j.name} never got a view. $at")
                assertEquals(j.newestSeq, j.shownSeq, "${j.name} is not on the newest view. $at")
            }
        }
    }

    /**
     * Walks every order and returns how many it checked. With [reduce], two orders that only swap independent steps are
     * walked once (a sleep set): a joiner's read or cut changes only that joiner, so it commutes with any step of
     * another joiner and with the host's steps. Every end state is still reached; only the count shrinks.
     */
    private fun everyOrder(names: List<String>, cuts: Int, reduce: Boolean = false, ends: MutableSet<String> = mutableSetOf()): Int {
        fun owner(step: String) = step.substringBefore(" ").takeIf { it in names }
        fun local(step: String) = step.endsWith(" read") || step.endsWith(" cut")
        fun independent(a: String, b: String) = owner(a) != owner(b) && (local(a) || local(b))
        var orders = 0
        fun explore(path: List<String>, sleep: Set<String>) {
            val world = World(names, cuts).apply { path.forEach(::play) }
            val next = world.steps()
            if (next.isEmpty()) {
                orders++
                world.assertSettled(path)
                ends += world.end()
            }
            val done = mutableSetOf<String>()
            (next - sleep).forEach { t ->
                explore(path + t, if (reduce) (sleep + done).filterTo(mutableSetOf()) { independent(it, t) } else emptySet())
                done += t
            }
        }
        explore(emptyList(), emptySet())
        return orders
    }

    @Test
    fun givenOneCutSocket_whenEventsLandInAnyOrder_thenOneSeatAndTheNewestView() {
        assertTrue(everyOrder(listOf("Asha"), cuts = 1) >= 90)
    }

    @Test
    fun givenTwoCutSockets_whenEventsLandInAnyOrder_thenOneSeatAndTheNewestView() {
        assertTrue(everyOrder(listOf("Asha"), cuts = 2) >= 4000)
    }

    @Test
    fun givenTheReducedWalk_whenComparedWithTheFullWalk_thenItReachesTheSameEndStates() {
        val full = mutableSetOf<String>()
        val reduced = mutableSetOf<String>()
        val all = everyOrder(listOf("Asha"), cuts = 2, ends = full)
        val fewer = everyOrder(listOf("Asha"), cuts = 2, reduce = true, ends = reduced)
        assertTrue(fewer < all, "the reduction skipped nothing: $fewer of $all")
        assertEquals(full, reduced)
    }

    @Test
    fun givenTwoJoinersAndNoCut_whenEventsLandInAnyOrder_thenEachHasOneSeatAndTheNewestView() {
        assertTrue(everyOrder(listOf("Asha", "Ben"), cuts = 0, reduce = true) >= 16)
    }

    @Test
    fun givenTwoJoinersAndOneCutEach_whenEventsLandInAnyOrder_thenEachHasOneSeatAndTheNewestView() {
        assertTrue(everyOrder(listOf("Asha", "Ben"), cuts = 1, reduce = true) >= 90_000)
    }
}
