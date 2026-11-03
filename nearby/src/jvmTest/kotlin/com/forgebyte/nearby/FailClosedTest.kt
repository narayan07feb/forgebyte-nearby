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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A joiner facing a host whose key does not match the code must fail closed: nothing but its key commitment ever
 * leaves it (no key reveal, no encrypted frame, so no name, room code, token or move), it never reaches Connected,
 * and a wrong key ends HostKeyMismatch at once without retrying. The fake host records every frame it receives.
 */
@OptIn(ExperimentalEncodingApi::class)
class FailClosedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val received = Collections.synchronizedList(mutableListOf<String>()) // "text:<json>" or "binary:<size>"
    private var connections = 0
    private val timeline = Collections.synchronizedList(mutableListOf<String>())

    @AfterTest
    fun tearDown() {
        traceSink = null
        scope.cancel()
    }

    /** A host that answers the commitment after [replyAfter] with [hostKey] (null: never answers). */
    private suspend fun fakeHost(hostKey: HostKey?, replyAfter: Duration = Duration.ZERO): Int {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket(WS_PATH) {
                    synchronized(received) { connections++ }
                    for (frame in incoming) {
                        received += when (frame) {
                            is Frame.Text -> "text:${frame.readText()}"
                            else -> "binary:${frame.data.size}"
                        }
                        if (hostKey != null && received.size == 1) {
                            delay(replyAfter)
                            val reply = Kx2(CRYPTO_VERSION, Base64.encode(hostKey.publicKey), Base64.encode(ByteArray(16)))
                            send(Frame.Text(NearbyProtocol.json.encodeToString(Kx2.serializer(), reply)))
                        }
                    }
                }
            }
        }
        server.startSuspend(wait = false)
        return server.engine.resolvedConnectors().first().port
    }

    private fun client(firstConnectFor: Duration = 10.seconds) =
        NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope, firstConnectFor)

    private suspend fun joinWithCode(port: Int, expected: HostKey, c: NearbyClient<FakeMove, FakeView>): ClientStatus {
        traceSink = { timeline += it }
        val seen = Collections.synchronizedList(mutableListOf<ClientStatus>())
        scope.launch { c.status.collect { seen += it } }
        c.join(JoinCode("127.0.0.1", port, Harness.SECRET, expected.fingerprint), "Asha")
        val end = c.status.first { it is ClientStatus.Ended }
        assertTrue(ClientStatus.Connected !in seen && ClientStatus.WaitingForHost !in seen, "never got in: $seen")
        assertNull(c.checkCode.value)
        assertNull(c.lobby.value)
        return end
    }

    private fun assertOnlyCommitmentsSent() {
        val frames = received.toList()
        assertTrue(frames.isNotEmpty(), "the fake host saw the connection")
        assertTrue(frames.all { it.startsWith("text:") && "\"commit\"" in it }, "only key commitments, got $frames")
        listOf("Asha", Harness.SECRET, "\"key\"", "binary:").forEach { leak ->
            assertTrue(frames.none { leak in it }, "$leak must never be sent; got $frames")
        }
    }

    @Test
    fun givenHostWithAnotherKey_whenJoiningByCode_thenHostKeyMismatchAtOnceNothingSentNoRetry() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = fakeHost(HostKey.create())
            val end = joinWithCode(port, HostKey.create(), client())
            assertEquals(ClientStatus.Ended(EndReason.HostKeyMismatch), end, "timeline $timeline")
            assertOnlyCommitmentsSent()
            delay(1_500) // a retry would have connected again by now
            assertEquals(1, connections, "a wrong host key is never retried")
        }
    }

    @Test
    fun givenHostWithAnotherKeyThatAnswersSlowly_whenJoiningByCode_thenStillHostKeyMismatchAndNoRetry() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = fakeHost(HostKey.create(), replyAfter = 3.seconds)
            val end = joinWithCode(port, HostKey.create(), client())
            assertEquals(ClientStatus.Ended(EndReason.HostKeyMismatch), end, "timeline $timeline")
            assertOnlyCommitmentsSent()
            assertEquals(1, connections)
        }
    }

    @Test
    fun givenHostThatNeverAnswers_whenJoiningByCode_thenEndsUnreachableWithNothingButCommitmentsSent() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val port = fakeHost(hostKey = null)
            val end = joinWithCode(port, HostKey.create(), client(firstConnectFor = 3.seconds))
            assertEquals(ClientStatus.Ended(EndReason.Unreachable), end, "timeline $timeline")
            assertOnlyCommitmentsSent()
        }
    }
}
