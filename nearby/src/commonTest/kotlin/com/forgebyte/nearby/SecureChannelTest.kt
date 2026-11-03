package com.forgebyte.nearby

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class SecureChannelTest {
    /** Two in-memory pipes standing in for a WebSocket; [joinerSent] records what the joiner put on the wire. */
    private class Wire {
        val toHost = Channel<String>(Channel.UNLIMITED)
        val toJoiner = Channel<String>(Channel.UNLIMITED)
        val joinerSent = mutableListOf<String>()
        val hostSide = Pair<suspend () -> String?, suspend (String) -> Unit>({ toHost.receiveCatching().getOrNull() }, { toJoiner.send(it) })
        val joinerSide = Pair<suspend () -> String?, suspend (String) -> Unit>(
            { toJoiner.receiveCatching().getOrNull() },
            { joinerSent += it; toHost.send(it) },
        )
    }

    private suspend fun pair(key: HostKey, fingerprint: String? = key.fingerprint): Pair<SecureSession?, Handshake> = coroutineScope {
        val w = Wire()
        val host = async { hostHandshake(key, w.hostSide.first, w.hostSide.second) }
        val joiner = joinerHandshake(fingerprint, w.joinerSide.first, w.joinerSide.second)
        if (joiner !is Handshake.Ok) w.toHost.close()
        host.await() to joiner
    }

    @Test
    fun givenBothSides_whenKeysAgreed_thenMessagesFlowBothWaysAndCheckCodesMatch() = runTest {
        val (host, shake) = pair(HostKey.create())
        val joiner = assertIs<Handshake.Ok>(shake).session
        assertEquals(host!!.checkCode, joiner.checkCode)
        assertTrue(Regex("\\d{6}").matches(joiner.checkCode))
        assertEquals("hello host", host.open(joiner.seal("hello host")))
        assertEquals("hello joiner", joiner.open(host.seal("hello joiner")))
        assertEquals("second", host.open(joiner.seal("second")))
    }

    @Test
    fun givenSealedFrame_whenOneByteChanged_thenRefused() = runTest {
        val (host, shake) = pair(HostKey.create())
        val frame = (shake as Handshake.Ok).session.seal("""{"t":"ready","ready":true}""")
        frame[frame.size / 2] = (frame[frame.size / 2].toInt() xor 1).toByte()
        assertNull(host!!.open(frame))
    }

    @Test
    fun givenFrameAlreadyRead_whenSentAgain_thenRefused() = runTest {
        val (host, shake) = pair(HostKey.create())
        val frame = (shake as Handshake.Ok).session.seal("move C1Z")
        assertEquals("move C1Z", host!!.open(frame))
        assertNull(host.open(frame), "a replayed frame must not be accepted")
    }

    @Test
    fun givenTwoFrames_whenReadOutOfOrder_thenRefused() = runTest {
        val (host, shake) = pair(HostKey.create())
        val joiner = (shake as Handshake.Ok).session
        joiner.seal("first")
        val second = joiner.seal("second")
        assertNull(host!!.open(second))
    }

    @Test
    fun givenFrameForTheOtherDirection_whenOpened_thenRefused() = runTest {
        val (host, shake) = pair(HostKey.create())
        val joiner = (shake as Handshake.Ok).session
        val frame = host!!.seal("to joiner")
        assertNull(host.open(frame), "each direction has its own key")
        assertEquals("to joiner", joiner.open(frame))
    }

    @Test
    fun givenCodeWithOtherFingerprint_whenJoining_thenRefusedBeforeRevealingKey() = runTest {
        val w = Wire()
        val key = HostKey.create()
        val result = coroutineScope {
            val host = async { hostHandshake(key, w.hostSide.first, w.hostSide.second) }
            val r = joinerHandshake("0123456789abcdef", w.joinerSide.first, w.joinerSide.second)
            w.toHost.close()
            assertNull(host.await())
            r
        }
        assertEquals(Handshake.WrongHostKey, result)
        assertEquals(1, w.joinerSent.size, "only the commitment went out, never the joiner's key")
    }

    @Test
    fun givenJoinerRevealsAnotherKey_whenHostChecksCommitment_thenRefused() = runTest {
        val w = Wire()
        val key = HostKey.create()
        val other = HostKey.create().publicKey // a valid key that is not the committed one
        val host = coroutineScope {
            val host = async { hostHandshake(key, w.hostSide.first, w.hostSide.second) }
            val honest = Wire()
            // Run a real joiner against a recorder to get a well-formed commitment, then reveal another key.
            val joiner = async { joinerHandshake(null, honest.joinerSide.first, honest.joinerSide.second) }
            w.toHost.send(honest.toHost.receive()) // Kx1 commitment
            honest.toJoiner.close()
            joiner.await()
            w.toJoiner.receive() // Kx2
            w.toHost.send(NearbyProtocol.json.encodeToString(Kx3.serializer(), Kx3(kotlin.io.encoding.Base64.encode(other))))
            host.await()
        }
        assertNull(host)
    }

    @Test
    fun givenOtherCryptoVersion_whenJoining_thenBothSidesStop() = runTest {
        val w = Wire()
        val key = HostKey.create()
        w.toHost.send(NearbyProtocol.json.encodeToString(Kx1.serializer(), Kx1(CRYPTO_VERSION + 1, "x")))
        assertNull(hostHandshake(key, w.hostSide.first, w.hostSide.second))
        val reply = NearbyProtocol.json.decodeFromString(Kx2.serializer(), w.toJoiner.receive())
        assertEquals(CRYPTO_VERSION, reply.v)

        val w2 = Wire()
        w2.toJoiner.send(NearbyProtocol.json.encodeToString(Kx2.serializer(), Kx2(CRYPTO_VERSION + 1, "", "")))
        assertEquals(Handshake.VersionMismatch, joinerHandshake(null, w2.joinerSide.first, w2.joinerSide.second))
    }

    @Test
    fun givenSomeoneInTheMiddle_whenBothSidesFinish_thenCheckCodesDiffer() = runTest {
        // The attacker runs a host towards the joiner and a joiner towards the host, with its own keys.
        val realHost = HostKey.create()
        val fakeHost = HostKey.create()
        val (hostSide, attackerAsJoiner) = pair(realHost, fingerprint = null)
        val (attackerAsHost, joinerSide) = pair(fakeHost, fingerprint = null)
        val joinerCode = (joinerSide as Handshake.Ok).session.checkCode
        assertEquals(attackerAsHost!!.checkCode, joinerCode)
        assertEquals(hostSide!!.checkCode, (attackerAsJoiner as Handshake.Ok).session.checkCode)
        assertNotEquals(hostSide.checkCode, joinerCode, "the two screens show different codes (1 in a million to match)")
    }

    @Test
    fun givenTwoRooms_whenKeysMade_thenEachRoomHasItsOwnKey() = runTest {
        val a = HostKey.create()
        val b = HostKey.create()
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertTrue(Regex("[0-9a-f]{16}").matches(a.fingerprint))
        assertEquals(a.fingerprint, fingerprintOf(a.publicKey))
    }

    @Test
    fun givenJunkInsteadOfAKey_whenHostReadsIt_thenRefused() = runTest {
        val w = Wire()
        val junk = ByteArray(65) { 4 }
        val commit = kotlin.io.encoding.Base64.encode(
            cryptoProvider.get(dev.whyoleg.cryptography.algorithms.SHA256).hasher().hash(junk),
        )
        w.toHost.send(NearbyProtocol.json.encodeToString(Kx1.serializer(), Kx1(CRYPTO_VERSION, commit)))
        w.toHost.send(NearbyProtocol.json.encodeToString(Kx3.serializer(), Kx3(kotlin.io.encoding.Base64.encode(junk))))
        assertNull(hostHandshake(HostKey.create(), w.hostSide.first, w.hostSide.second))
    }
}
