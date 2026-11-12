package com.forgebyte.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    private val smile = "😀" // 😀, two UTF-16 units
    private val hindi = "राहुल कुमार शर्मा जी नमस्ते"

    private fun assertWhole(text: String) = text.forEachIndexed { i, c ->
        if (c.isHighSurrogate()) assertTrue(i + 1 < text.length && text[i + 1].isLowSurrogate(), "half emoji at $i in $text")
        if (c.isLowSurrogate()) assertTrue(i > 0 && text[i - 1].isHighSurrogate(), "half emoji at $i in $text")
    }

    @Test
    fun givenEmojiAcrossTheLimit_whenCut_thenTheWholeEmojiIsLeftOut() {
        assertEquals("a".repeat(19), ("a".repeat(19) + smile + "b").cutText(20))
        assertEquals(smile.repeat(10), smile.repeat(15).cutText(20))
        assertEquals("Ravi$smile", "Ravi$smile".cutText(20))
    }

    @Test
    fun givenEmojiWithVariationSelectorOrZwj_whenCutInside_thenTheWholeSequenceIsLeftOut() {
        val heart = "❤️" // ❤️
        assertEquals("a".repeat(19), ("a".repeat(19) + heart).cutText(20))
        val family = "👨‍👩" // man ZWJ woman
        assertEquals("a".repeat(16), ("a".repeat(16) + family).cutText(20))
    }

    @Test
    fun givenHindiAcrossTheLimit_whenCut_thenNoVowelSignIsCutOffItsLetter() {
        // "कु" is the letter क plus the vowel sign ु: a cut between them would leave क with a dangling sign.
        val name = "a".repeat(19) + "कु"
        assertEquals("a".repeat(19), name.cutText(20))
        val cut = hindi.cutText(20)
        assertTrue(hindi.startsWith(cut))
        assertTrue(cut.length <= 20)
        assertTrue(hindi[cut.length].category !in setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK))
    }

    @Test
    fun givenShortText_whenCut_thenUnchanged() {
        assertEquals("", "".cutText(20))
        assertEquals("R", "R".cutText(20))
        assertEquals(hindi, hindi.cutText(100))
    }

    @Test
    fun givenByteLimit_whenCut_thenFitsInUtf8AndEndsOnAWholeCharacter() {
        val cut = hindi.cutText(40, maxUtf8Bytes = 63)
        assertTrue(cut.encodeToByteArray().size <= 63, "${cut.encodeToByteArray().size} bytes")
        assertTrue(cut.isNotEmpty() && hindi.startsWith(cut))
        val emoji = smile.repeat(30).cutText(40, maxUtf8Bytes = 63)
        assertEquals(smile.repeat(15), emoji) // 15 x 4 bytes = 60
        assertWhole(emoji)
    }

    @Test
    fun givenEmojiNameAtTheLimit_whenJoins_thenSeatNameHasNoHalfEmoji() {
        val name = Harness(FakeGame()).hello("a", name = "a".repeat(19) + smile + "b").welcome("a").lobby.seats[1].name
        assertEquals("a".repeat(19), name)
    }

    @Test
    fun givenLongRoomNames_whenAdvertised_thenTxtAndServiceNameEndOnWholeCharacters() {
        val config = Harness.config().let { RoomConfig("x".repeat(39) + smile, it.hostName, it.gameVersion, it.roomSecret, it.maxSeats) }
        assertEquals("x".repeat(39), roomTxt("fake", config, 1)[Txt.NAME])
        val hindiRoom = RoomConfig("$hindi $hindi", "Host", 1, "s", 4)
        val service = serviceName(hindiRoom)
        assertTrue(service.encodeToByteArray().size <= 63)
        assertTrue(hindiRoom.roomName.startsWith(service) && service.isNotEmpty())
        assertEquals("Nearby test Mi A3", serviceName(RoomConfig("Nearby test Mi A3", "Host", 1, "s", 4)))
    }
}
