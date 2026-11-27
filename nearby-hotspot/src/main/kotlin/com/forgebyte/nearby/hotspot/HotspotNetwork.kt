package com.forgebyte.nearby.hotspot

/**
 * A hotspot's network name and WPA2 password. Android picks both at random for each hotspot start.
 *
 * Never log or save it: [toString] leaves the password out. Show [wifiQr] only on the host screen, to the players at
 * the table.
 */
public data class HotspotNetwork(val ssid: String, val password: String) {
    init {
        require(ssid.isNotEmpty()) { "ssid is empty" }
        require(password.isNotEmpty()) { "password is empty" }
    }

    /** The standard Wi-Fi QR text (`WIFI:T:WPA;S:...;P:...;;`), which phone cameras can join too. */
    public fun wifiQr(): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"

    override fun toString(): String = "HotspotNetwork(ssid=$ssid)"

    public companion object {
        private const val SPECIAL = "\\;,:\""

        /**
         * Reads a scanned Wi-Fi QR (fields in any order, any case of `WIFI:`). Null when it is not a Wi-Fi QR, or has no
         * network name or password (an open network is not a nearby hotspot).
         */
        public fun parseWifiQr(text: String): HotspotNetwork? {
            if (!text.startsWith("WIFI:", ignoreCase = true)) return null
            val fields = HashMap<String, String>()
            val field = StringBuilder()
            var i = 5
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length -> field.append(text[++i])
                    c == ';' -> {
                        val colon = field.indexOf(":")
                        if (colon > 0) fields.putIfAbsent(field.substring(0, colon).uppercase(), field.substring(colon + 1))
                        field.clear()
                    }
                    else -> field.append(c)
                }
                i++
            }
            if (fields["T"]?.uppercase() == "NOPASS") return null
            val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
            val password = fields["P"]?.takeIf { it.isNotEmpty() } ?: return null
            return HotspotNetwork(ssid, password)
        }

        private fun escape(value: String): String =
            buildString { value.forEach { if (it in SPECIAL) append('\\'); append(it) } }
    }
}
