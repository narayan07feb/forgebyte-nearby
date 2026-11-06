package com.forgebyte.nearby

/**
 * A six-character code that only finds the host on this Wi-Fi, for example `K7Q-2MX`. It holds the last byte of the
 * host's IPv4 address and the room's port; the joiner takes the first three bytes from its own address, so both
 * phones must be on the same /24 network (home Wi-Fi and most hotspots are).
 *
 * It carries no secret: a join by short code is a join request like a room found nearby. The host accepts or
 * declines it, and both screens show the same check code. Guessing a code only sends a request the host can decline.
 * The port is new each time a room opens, so a code is good for that room only.
 */
public data class ShortCode(val lastByte: Int, val port: Int) {
    init {
        require(lastByte in 1..254) { "lastByte must be 1..254" }
        require(port in 1..65535) { "port must be 1..65535" }
    }

    /** Five data characters (a zero version bit, then 24 bits) and one check character, as `XXX-XXX`. */
    override fun toString(): String {
        val value = (lastByte shl 16) or port
        val data = IntArray(DATA_CHARS) { i -> (value shr (5 * (DATA_CHARS - 1 - i))) and 31 }
        val text = (data + checkValue(data)).joinToString("") { ALPHABET[it].toString() }
        return text.take(3) + "-" + text.drop(3)
    }

    /** The host's address on the network of [myAddress] (this device's IPv4 address), or null if that is not IPv4. */
    public fun hostOn(myAddress: String): String? {
        val parts = myAddress.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.takeIf { it in 0..255 } == null }) return null
        return parts.take(3).joinToString(".") + ".$lastByte"
    }

    public companion object {
        /** Crockford base 32: no I, L, O or U, so a typed code is hard to misread. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val DATA_CHARS = 5
        private const val CHECK_MOD = 31
        private const val INVERSE_OF_6 = 26 // 6 * 26 = 156 = 5 * 31 + 1

        /** The code for a host at [address] (IPv4) listening on [port], or null if the address cannot have one. */
        public fun of(address: String, port: Int): ShortCode? {
            val last = address.split('.').takeIf { it.size == 4 }?.last()?.toIntOrNull() ?: return null
            return if (last in 1..254 && port in 1..65535) ShortCode(last, port) else null
        }

        /**
         * Reads a typed code: any case, dashes and spaces ignored, O read as 0, I and L as 1. Null when the check
         * character does not match (a typo), a character is not in the code alphabet, or the code is from a newer version.
         */
        public fun parse(text: String): ShortCode? {
            val values = text.uppercase().filter { it != '-' && !it.isWhitespace() }.map { c ->
                when (c) {
                    'O' -> 0
                    'I', 'L' -> 1
                    else -> ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
                }
            }
            if (values.size != DATA_CHARS + 1) return null
            val data = values.take(DATA_CHARS).toIntArray()
            if (values.last() != checkValue(data)) return null
            val value = data.fold(0) { acc, v -> (acc shl 5) or v }
            val last = value shr 16 // a set version bit (a newer code) makes this 256 or more, so it is refused below
            val port = value and 0xFFFF
            return if (last in 1..254 && port in 1..65535) ShortCode(last, port) else null
        }

        /**
         * The check value makes sum((i + 1) * v[i]) over all six characters a multiple of 31. It catches every single
         * wrong character and every swap of two neighbours, except a mix-up between 0 and Z.
         */
        private fun checkValue(data: IntArray): Int {
            val sum = data.withIndex().sumOf { (i, v) -> (i + 1) * v }
            return ((CHECK_MOD - sum % CHECK_MOD) * INVERSE_OF_6) % CHECK_MOD
        }
    }
}
