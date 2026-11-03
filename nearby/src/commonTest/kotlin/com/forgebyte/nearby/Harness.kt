package com.forgebyte.nearby

import kotlinx.serialization.json.JsonElement

/** A room with a fake clock and predictable tokens, plus helpers to read what it sent. */
class Harness<S, M, V>(
    game: NearbyGame<S, M, V>,
    val config: RoomConfig = config(),
) {
    var now = 0L
    private var tokens = 0
    val room = Room(game, config, clock = { now }, newToken = { "tok${++tokens}" })

    fun hello(
        conn: String,
        name: String = conn,
        token: String? = null,
        protocol: Int = PROTOCOL_VERSION,
        gameId: String = "fake",
        gameVersion: Int = GAME_VERSION,
        secret: String? = SECRET,
    ) = room.onMessage(conn, ClientMsg.Hello(protocol, gameId, gameVersion, name, secret, token))

    /** A join request: a hello without the room code. */
    fun request(conn: String, name: String = conn, gameVersion: Int = GAME_VERSION) =
        hello(conn, name, secret = null, gameVersion = gameVersion)

    companion object {
        const val GAME_VERSION = 7
        const val SECRET = "s3cret"
        const val WINDOW = 30_000L

        const val REQUEST_TIMEOUT = 20_000L

        fun config(maxSeats: Int = 4, minSeats: Int = 2, maxPending: Int = 4) = RoomConfig(
            roomName = "Den",
            hostName = "Host",
            gameVersion = GAME_VERSION,
            roomSecret = SECRET,
            maxSeats = maxSeats,
            minSeats = minSeats,
            reconnectWindowMs = WINDOW,
            requestTimeoutMs = REQUEST_TIMEOUT,
            maxPendingRequests = maxPending,
        )
    }
}

fun List<RoomOutput>.to(conn: String): List<HostMsg> =
    filterIsInstance<RoomOutput.Send>().filter { it.conn == conn }.map { it.msg }

fun List<RoomOutput>.closed(conn: String) = RoomOutput.Close(conn) in this

fun List<RoomOutput>.welcome(conn: String) = to(conn).filterIsInstance<HostMsg.Welcome>().single()

fun List<RoomOutput>.lastLobby(conn: String): Lobby =
    to(conn).mapNotNull { (it as? HostMsg.LobbyUpdate)?.lobby ?: (it as? HostMsg.Welcome)?.lobby }.last()

fun List<RoomOutput>.views(conn: String): List<FakeView> = to(conn).filterIsInstance<HostMsg.View>()
    .map { NearbyProtocol.json.decodeFromJsonElement(FakeView.serializer(), it.view) }

fun move(card: String): ClientMsg.Move =
    ClientMsg.Move(NearbyProtocol.json.encodeToJsonElement(FakeMove.serializer(), FakeMove(card)))

fun jsonMove(element: JsonElement) = ClientMsg.Move(element)
