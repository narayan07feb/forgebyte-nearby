package com.forgebyte.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Security, uno #59 review: the default deal seed comes from the secure random source, never kotlin Random. */
class DefaultSeedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class SeedRecorder : FakeGame() {
        val seeds = mutableListOf<Long>()
        override fun newGame(seats: Int, seed: Long): FakeState = super.newGame(seats, seed).also { seeds += seed }
    }

    /** Starts a host with one bot and no seed argument; returns the seed the game was dealt with. */
    private fun defaultStart(randomBytes: ((Int) -> ByteArray)? = null): Long = runBlocking {
        val game = SeedRecorder()
        val host = NearbyHost(game, Harness.config(), scope)
        randomBytes?.let { host.randomBytes = it }
        host.addBot()
        host.startGame()
        game.seeds.single()
    }

    @Test
    fun givenTwoHosts_whenEachStartsWithTheDefaultSeed_thenTheSeedsDiffer() {
        assertNotEquals(defaultStart(), defaultStart())
    }

    @Test
    fun givenAFakeSecureSource_whenStartWithTheDefaultSeed_thenTheSeedIsExactlyItsBytes() {
        val asked = mutableListOf<Int>()
        val seed = defaultStart { size -> asked += size; ByteArray(size) { (it + 1).toByte() } }
        assertEquals(listOf(SEED_BYTES), asked, "one call to the secure source, for 8 bytes")
        assertEquals(0x0102030405060708L, seed, "the seed is those bytes and nothing else (no kotlin Random mixed in)")
    }

    @Test
    fun givenHighBytes_whenToSeed_thenNoSignSpill() {
        assertEquals(-1L, ByteArray(8) { -1 }.toSeed())
        assertEquals(0x00000000000000ffL, (ByteArray(7) + byteArrayOf(-1)).toSeed())
    }
}
