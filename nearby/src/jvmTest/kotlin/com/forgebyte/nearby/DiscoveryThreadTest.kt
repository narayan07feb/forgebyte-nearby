package com.forgebyte.nearby

import com.appstractive.dnssd.DiscoveryEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/** Discovery never runs dns-sd-kt's work on the collector's thread (an app collecting on Main must not crash). */
class DiscoveryThreadTest {
    @Test
    fun discoveryEventsAreReadOffTheCollectorsThread() = runBlocking {
        val collector = Thread.currentThread()
        var upstream: Thread? = null
        flow<DiscoveryEvent> { upstream = Thread.currentThread() }.toRooms("fake").toList()
        assertNotNull(upstream)
        assertNotSame(collector, upstream)
    }
}
