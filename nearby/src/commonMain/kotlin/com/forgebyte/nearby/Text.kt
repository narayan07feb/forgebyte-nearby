package com.forgebyte.nearby

/**
 * The first [maxChars] UTF-16 units of this text (and at most [maxUtf8Bytes] bytes in UTF-8), cut only where a
 * character starts: never inside an emoji (a surrogate pair), never between a letter and its vowel sign or mark
 * (Hindi "कु"), and never inside a joined emoji sequence (ZWJ, variation selector). A plain [take] can leave half an
 * emoji, which shows as a broken character.
 */
internal fun String.cutText(maxChars: Int, maxUtf8Bytes: Int = Int.MAX_VALUE): String {
    var end = 0
    var bytes = 0
    while (end < length) {
        val pair = this[end].isHighSurrogate() && end + 1 < length && this[end + 1].isLowSurrogate()
        val units = if (pair) 2 else 1
        val size = if (pair) 4 else utf8Size(this[end])
        if (end + units > maxChars || bytes + size > maxUtf8Bytes) break
        end += units
        bytes += size
    }
    while (end in 1 until length && (this[end].isLowSurrogate() || this[end].isMark() || this[end] == ZWJ || this[end - 1] == ZWJ)) end--
    return substring(0, end)
}

private const val ZWJ = '‍'

private fun Char.isMark() =
    category == CharCategory.NON_SPACING_MARK || category == CharCategory.COMBINING_SPACING_MARK ||
        category == CharCategory.ENCLOSING_MARK

private fun utf8Size(c: Char) = when {
    c.code < 0x80 -> 1
    c.code < 0x800 -> 2
    else -> 3
}
