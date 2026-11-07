package com.forgebyte.nearby

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A joiner whose app was killed takes its seat back with the [SavedSeat] its [SeatStore] kept (ceo, 7 Nov). */
@OptIn(ExperimentalEncodingApi::class)
class SeatResumeTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val window = 1_500L
    private val host = NearbyHost(FakeGame(cardsEach = 6), Harness.config().let {
        RoomConfig(it.roomName, it.hostName, it.gameVersion, it.roomSecret, it.maxSeats, reconnectWindowMs = window, turnTimeoutMs = 0, botDelayMs = 0)
    }, scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        host.leave()
        scope.cancel()
    }

    /** Each client gets its own scope, so cancelling it is like the app being killed: nothing is sent, nothing cleared. */
    private fun client(store: SeatStore, own: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), own, seatStore = store) to own

    private suspend fun NearbyClient<FakeMove, FakeView>.until(vararg s: ClientStatus) =
        status.first { it in s || it is ClientStatus.Ended }

    /** Host plus one joiner by code, game started. */
    private suspend fun startedGame(store: SeatStore): Pair<NearbyClient<FakeMove, FakeView>, CoroutineScope> {
        val port = host.open(bindHost = LOOPBACK)
        val (c, own) = client(store)
        c.join(JoinCode(LOOPBACK, port, Harness.SECRET, host.fingerprint!!), "Asha")
        assertEquals(ClientStatus.Connected, c.until(ClientStatus.Connected))
        c.ready(true)
        host.canStart.first { it }
        host.startGame()
        c.seqView.first { it != null }
        return c to own
    }

    @Test
    fun givenJoinedSeat_whenWelcomed_thenStoreHoldsTheRoomKeyAndToken() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val store = InMemorySeatStore()
            startedGame(store)
            val saved = assertNotNull(store.load())
            assertEquals(host.fingerprint, saved.fingerprint)
            assertEquals("fake", saved.gameId)
            assertEquals("Asha", saved.name)
            assertEquals(saved, SavedSeat.parse(saved.toText()))
            assertNull(SavedSeat.parse("not a seat"))
        }
    }

    @Test
    fun givenAppKilledMidGame_whenReopenedAndResumed_thenSameSeatBackWithTheCurrentView() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val store = InMemorySeatStore()
            val (_, own) = startedGame(store)
            own.cancel() // the app is killed
            host.lobby.first { !it.seats[1].connected }
            assertNotNull(store.load(), "a kill does not clear the saved seat")

            val (again, _) = client(store)
            again.resume(store.load()!!)
            assertEquals(ClientStatus.Connected, again.until(ClientStatus.Connected))
            assertEquals(1, again.lobby.value!!.yourSeat)
            assertNotNull(again.seqView.first { it != null })
            val seat = host.lobby.first { it.seats[1].connected }.seats[1]
            assertEquals(SeatKind.PLAYER, seat.kind)
            assertEquals(2, host.lobby.value.seats.size, "no new seat")
        }
    }

    @Test
    fun givenBotTookTheSeatAfterTheWindow_whenResumed_thenThePlayerGetsItBack() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val store = InMemorySeatStore()
            val (_, own) = startedGame(store)
            own.cancel()
            host.lobby.first { it.seats[1].kind == SeatKind.BOT }

            val (again, _) = client(store)
            again.resume(store.load()!!)
            assertEquals(ClientStatus.Connected, again.until(ClientStatus.Connected))
            assertEquals(SeatKind.PLAYER, host.lobby.first { it.seats[1].connected && it.seats[1].kind == SeatKind.PLAYER }.seats[1].kind)
        }
    }

    @Test
    fun givenPlayerLeft_whenOldSeatResumed_thenRefusedAndStoreCleared() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val store = InMemorySeatStore()
            val (c, _) = startedGame(store)
            val old = store.load()!!
            c.leave()
            c.status.first { it is ClientStatus.Ended }
            assertNull(store.load(), "leaving clears the saved seat")

            store.save(old) // as if the clear had not reached storage
            val (again, _) = client(store)
            again.resume(old)
            val end = again.status.first { it is ClientStatus.Ended }
            assertTrue((end as ClientStatus.Ended).reason is EndReason.Refused, "got $end")
            assertNull(store.load())
        }
    }

    @Test
    fun givenSavedSeatFromAnotherRoom_whenAJoinIsRefusedBeforeAnySeat_thenTheStoreIsKept() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val other = SavedSeat("10.0.0.9", 4000, "0123456789abcdef", "fake", "Asha", "tok-other")
            val store = InMemorySeatStore().apply { save(other) }
            val port = host.open(bindHost = LOOPBACK)
            val (c, _) = client(store)
            c.join(JoinCode(LOOPBACK, port, "wrong secret", host.fingerprint!!), "Asha")
            val end = c.status.first { it is ClientStatus.Ended }
            assertTrue((end as ClientStatus.Ended).reason is EndReason.Refused, "got $end")
            assertEquals(other, store.load())
        }
    }

    @Test
    fun givenAnotherHostOnTheSavedAddress_whenResumed_thenHostKeyMismatchAndTheTokenIsNeverSent() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val received = Collections.synchronizedList(mutableListOf<String>())
            val impostor = HostKey.create()
            val server = embeddedServer(CIO, port = 0, host = LOOPBACK) {
                install(WebSockets)
                routing {
                    webSocket(WS_PATH) {
                        for (frame in incoming) {
                            received += if (frame is Frame.Text) "text:${frame.readText()}" else "binary:${frame.data.size}"
                            if (received.size == 1) {
                                val reply = Kx2(CRYPTO_VERSION, Base64.encode(impostor.publicKey), Base64.encode(ByteArray(16)))
                                send(Frame.Text(NearbyProtocol.json.encodeToString(Kx2.serializer(), reply)))
                            }
                        }
                    }
                }
            }
            server.startSuspend(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                val saved = SavedSeat(LOOPBACK, port, HostKey.create().fingerprint, "fake", "Asha", "tok-secret-seat")
                val store = InMemorySeatStore().apply { save(saved) }
                val (c, _) = client(store)
                c.resume(saved)
                assertEquals(ClientStatus.Ended(EndReason.HostKeyMismatch), c.status.first { it is ClientStatus.Ended })
                val frames = received.toList()
                assertTrue(frames.isNotEmpty() && frames.all { it.startsWith("text:") && "\"commit\"" in it }, "only key commitments: $frames")
                assertTrue(frames.none { "tok-secret-seat" in it || "Asha" in it || it.startsWith("binary:") }, "token or name leaked: $frames")
            } finally {
                server.stop(0, 0)
            }
        }
    }
}
