package com.forgebyte.nearby

import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import kotlin.concurrent.Volatile

/**
 * Android setup for discovery. Call [init] when the player opens nearby play (before [discoverRooms] or
 * [NearbyHost.advertise]); nothing nearby-related runs at app start.
 */
public object NearbyAndroid {
    /** dns-sd-kt's androidx.startup initializer; it only keeps the application context. Internal in Kotlin. */
    internal const val DNSSD_INITIALIZER: String = "com.appstractive.dnssd.AppContextInitializer"

    @Volatile private var ready = false

    /** Safe to call more than once; any Context will do, the application context is kept. */
    public fun init(context: Context) {
        if (ready) return
        @Suppress("UNCHECKED_CAST")
        val initializer = Class.forName(DNSSD_INITIALIZER) as Class<out Initializer<Any>>
        AppInitializer.getInstance(context.applicationContext).initializeComponent(initializer)
        ready = true
    }

    internal fun checkReady() {
        check(ready) { "Call NearbyAndroid.init(context) before nearby discovery on Android." }
    }
}

internal actual fun ensureDiscoveryReady(): Unit = NearbyAndroid.checkReady()
