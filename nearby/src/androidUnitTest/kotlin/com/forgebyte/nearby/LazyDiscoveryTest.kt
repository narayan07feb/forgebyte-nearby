package com.forgebyte.nearby

import androidx.startup.Initializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ceo, 2026-11-03: discovery is set up on first use, not at app start. */
class LazyDiscoveryTest {
    @Test
    fun givenDnsSdOnTheClasspath_whenLookingUpItsInitializer_thenTheNameStillResolves() {
        // Guards a dns-sd-kt upgrade that renames its internal initializer: init() would fail at runtime.
        val type = Class.forName(NearbyAndroid.DNSSD_INITIALIZER)
        assertTrue(Initializer::class.java.isAssignableFrom(type))
    }

    @Test
    fun givenInitNotCalled_whenDiscovering_thenClearError() = runTest {
        val e = assertFailsWith<IllegalStateException> { discoverRooms("uno").first() }
        assertTrue("NearbyAndroid.init" in e.message.orEmpty())
    }
}
