package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [NearbyTrace] sends the connect lines to a game's debug log, and none of them holds a secret or a name. */
class NearbyTraceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = NearbyHost(FakeGame(), Harness.config(), scope)
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        NearbyTrace.sink = null
        host.leave()
        scope.cancel()
    }

    @Test
    fun givenSink_whenJoinByCode_thenConnectLinesWithNoSecretNameOrCheckCode() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            NearbyTrace.sink = { lines += it }
            val port = host.open(bindHost = LOOPBACK)
            val c = NearbyClient("fake", Harness.GAME_VERSION, FakeMove.serializer(), FakeView.serializer(), scope)
            c.join(JoinCode("127.0.0.1", port, Harness.SECRET, host.fingerprint!!), "Asha")
            assertEquals(ClientStatus.Connected, c.status.first { it == ClientStatus.Connected || it is ClientStatus.Ended })

            val all = lines.toList()
            listOf("joiner: connecting to", "joiner: key exchange Ok", "joiner: welcome, seat 1", "host: key exchange ok").forEach { want ->
                assertTrue(all.any { want in it }, "'$want' in $all")
            }
            val secrets = listOf("Asha", Harness.SECRET, c.checkCode.value!!, "Session")
            assertTrue(all.none { line -> secrets.any { it in line } }, "no name, room code, check code or session in $all")
        }
    }
}
