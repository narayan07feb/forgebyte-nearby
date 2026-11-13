package com.forgebyte.nearby.macclient

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun givenTraceBetweenPairs_whenParsing_thenPairsStayIntact() {
        val opts = parseOpts(listOf("--think", "6000", "--trace", "--code", "1.2.3.4:5:6:7"))
        assertEquals(mapOf("--think" to "6000", "--code" to "1.2.3.4:5:6:7"), opts)
    }

    @Test
    fun givenNoTrace_whenParsing_thenSamePairsAsBefore() {
        assertEquals(mapOf("--name" to "Mac", "--think" to "0"), parseOpts(listOf("--name", "Mac", "--think", "0")))
    }

    @Test
    fun givenTime_whenStamped_thenMillisClockPrefix() {
        assertEquals("09:05:03.007 [trace] joiner: socket open", stamped(LocalTime.of(9, 5, 3, 7_000_000), "[trace] joiner: socket open"))
    }
}
