package com.forgebyte.nearby

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private val lobby = Lobby(
        roomName = "Den",
        gameId = "uno",
        maxSeats = 4,
        phase = Phase.PLAYING,
        seats = listOf(
            SeatInfo("Host", SeatKind.HOST, ready = true, connected = true),
            SeatInfo("Asha", SeatKind.PLAYER, ready = false, connected = false),
            SeatInfo("Bot 1", SeatKind.BOT, ready = true, connected = true),
        ),
        yourSeat = 1,
    )
    private val json = JsonObject(mapOf("card" to JsonPrimitive("C1Z"), "n" to JsonPrimitive(3)))

    @Test
    fun givenEveryClientMessage_whenEncodedAndDecoded_thenEqual() {
        listOf(
            ClientMsg.Hello(1, "uno", 12, "Asha", "secret", reconnectToken = null),
            ClientMsg.Hello(1, "satti", 3, "Ravi", "secret", reconnectToken = "abc"),
            ClientMsg.Ready(true),
            ClientMsg.Move(json),
            ClientMsg.Leave,
        ).forEach { assertEquals(it, NearbyProtocol.decodeClient(NearbyProtocol.encode(it))) }
    }

    @Test
    fun givenEveryHostMessage_whenEncodedAndDecoded_thenEqual() {
        val messages = listOf(HostMsg.Welcome("tok", lobby), HostMsg.LobbyUpdate(lobby), HostMsg.View(9, json),
            HostMsg.MoveRejected("no"), HostMsg.Kicked, HostMsg.RoomClosed(CloseReason.HOST_LEFT)) +
            RefuseReason.entries.map { HostMsg.Refused(it, "why") }
        messages.forEach { assertEquals(it, NearbyProtocol.decodeHost(NearbyProtocol.encode(it))) }
    }

    @Test
    fun givenMessage_whenEncoded_thenTypeTagIsShortName() {
        assertTrue(NearbyProtocol.encode(ClientMsg.Ready(true)).contains("\"t\":\"ready\""))
        assertEquals("{\"t\":\"leave\"}", NearbyProtocol.encode(ClientMsg.Leave))
    }

    @Test
    fun givenExtraFieldFromNewerApp_whenDecoded_thenIgnored() {
        assertEquals(ClientMsg.Ready(false), NearbyProtocol.decodeClient("{\"t\":\"ready\",\"ready\":false,\"emoji\":1}"))
    }

    @Test
    fun givenJunk_whenDecoded_thenNull() {
        listOf("", "not json", "{}", "{\"t\":\"dance\"}", "{\"t\":\"ready\"}", "[1,2]", "{\"t\":\"ready\",\"ready\":\"x\"}")
            .forEach {
                assertNull(NearbyProtocol.decodeClient(it), it)
                assertNull(NearbyProtocol.decodeHost(it), it)
            }
    }
}
