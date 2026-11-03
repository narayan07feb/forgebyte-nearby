package com.forgebyte.nearby

import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureToken(): String =
    ByteArray(16).also(random::nextBytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
