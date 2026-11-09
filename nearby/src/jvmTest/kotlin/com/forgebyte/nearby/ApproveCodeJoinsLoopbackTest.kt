package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A full-code join to a room with approveCodeJoins waits for the host, with the same check code on both phones. */
class ApproveCodeJoinsLoopbackTest {
    @get:org.junit.Rule
    val traceRecorder = TraceRecorder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(), Harness.config(approveCodeJoins = true), scope)

    private val plain = NearbyHost(FakeGame(), Harness.config(), scope)
    private var relay: HoldRelay? = null

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        relay?.close()
        host.leave()
        withTimeoutOrNull(10.seconds) { plain.leave() }
        scope.cancel()
    }

    private fun client() = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)

    @Test
    fun givenApprovingRoom_whenJoinWithFullCode_thenWaitsForHostWithMatchingCheckCodeThenConnected() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val port = host.open(bindHost = LOOPBACK)
            val c = client()
            c.join(JoinCode("127.0.0.1", port, Harness.SECRET, host.fingerprint!!), "Asha")
            c.status.first { it == ClientStatus.WaitingForHost }
            val request = host.joinRequests.first { it.isNotEmpty() }.single()
            assertEquals(request.checkCode, c.checkCode.value)
            assertEquals(1, host.lobby.value.seats.size, "no seat before the host accepts")

            host.acceptJoin(request.id)
            c.status.first { it == ClientStatus.Connected }
            assertEquals(1, c.lobby.value!!.yourSeat)
        }
    }

    @Test
    fun givenDefaultRoom_whenJoinWithFullCode_thenNeverWaitingForHost() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            // The relay holds back the Welcome, so the joiner stays in the state it took after its Hello.
            val r = HoldRelay(plain.open(bindHost = LOOPBACK)).also { relay = it }
            val c = client()
            c.join(JoinCode("127.0.0.1", r.port, Harness.SECRET, plain.fingerprint!!), "Asha")
            plain.lobby.first { it.seats.size == 2 } // the host got the Hello and seated the joiner at once
            assertEquals(ClientStatus.Connecting, c.status.value)
        }
    }

    @Test
    fun givenApprovingRoom_whenHostDeclinesAFullCodeJoin_thenJoinerEndsDeclined() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val c = client()
            c.join(JoinCode("127.0.0.1", host.open(bindHost = LOOPBACK), Harness.SECRET, host.fingerprint!!), "Asha")
            host.declineJoin(host.joinRequests.first { it.isNotEmpty() }.single().id)
            val end = c.status.first { it is ClientStatus.Ended } as ClientStatus.Ended
            assertEquals(RefuseReason.DECLINED, (end.reason as EndReason.Refused).reason)
        }
    }

    /** Passes the joiner to [target]; from the host only the HTTP upgrade and the key exchange reach the joiner. */
    private class HoldRelay(target: Int) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = mutableListOf<Socket>()
        val port: Int = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val joiner = runCatching { server.accept() }.getOrNull() ?: break
                    val toHost = Socket(InetAddress.getLoopbackAddress(), target)
                    synchronized(sockets) { sockets += listOf(joiner, toHost) }
                    thread(isDaemon = true) { runCatching { joiner.getInputStream().copyTo(toHost.getOutputStream()) } }
                    thread(isDaemon = true) { runCatching { holdBinary(toHost.getInputStream(), joiner.getOutputStream()) } }
                }
            }
        }

        /** Copies the HTTP header and text frames; from the first binary frame (the encrypted Welcome) on, drops all. */
        private fun holdBinary(from: InputStream, out: OutputStream) {
            val header = ByteArrayOutputStream()
            while (!header.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) header.write(from.read())
            out.write(header.toByteArray())
            while (true) {
                val b0 = from.read()
                val b1 = from.read()
                var len = (b1 and 0x7f).toLong()
                val ext = ByteArrayOutputStream()
                if (len == 126L) { val x = from.readNBytes(2); ext.write(x); len = ((x[0].toInt() and 0xff) shl 8 or (x[1].toInt() and 0xff)).toLong() }
                if (len == 127L) { val x = from.readNBytes(8); ext.write(x); len = x.fold(0L) { a, v -> (a shl 8) or (v.toLong() and 0xff) } }
                val payload = from.readNBytes(len.toInt())
                if (b0 and 0x0f == 0x2) { from.copyTo(OutputStream.nullOutputStream()); return }
                out.write(b0); out.write(b1); out.write(ext.toByteArray()); out.write(payload)
            }
        }

        fun close() {
            server.close()
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }
    }
}
