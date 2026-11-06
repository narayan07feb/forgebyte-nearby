package com.forgebyte.nearby

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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

    /** The application context, for the Wi-Fi lock during a match. */
    @Volatile internal var appContext: Context? = null
        private set

    /** Safe to call more than once; any Context will do, the application context is kept. */
    public fun init(context: Context) {
        if (ready) return
        @Suppress("UNCHECKED_CAST")
        val initializer = Class.forName(DNSSD_INITIALIZER) as Class<out Initializer<Any>>
        AppInitializer.getInstance(context.applicationContext).initializeComponent(initializer)
        appContext = context.applicationContext
        ready = true
    }

    internal fun checkReady() {
        check(ready) { "Call NearbyAndroid.init(context) before nearby discovery on Android." }
    }
}

internal actual fun ensureDiscoveryReady(): Unit = NearbyAndroid.checkReady()

/**
 * Wi-Fi power saving can delay a packet by up to a beacon interval or more. Taken only while a match runs.
 * Low latency works only while the app is in front with the screen on; below API 29 the high-performance lock is
 * the closest. Needs WAKE_LOCK (in this library's manifest). A failure only costs latency, so it is traced, not thrown.
 */
internal actual fun newWifiLatencyLock(): WifiLatencyLock {
    val wifi = NearbyAndroid.appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return NoWifiLock.also { trace("wifi lock: no context (NearbyAndroid.init not called); none taken") }
    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
    val lock = wifi.createWifiLock(mode, "forgebyte-nearby").apply { setReferenceCounted(false) }
    return object : WifiLatencyLock {
        override fun acquire() = guard("acquire") { lock.acquire() }
        override fun release() = guard("release") { lock.release() }
    }
}

private object NoWifiLock : WifiLatencyLock {
    override fun acquire() {}
    override fun release() {}
}

private inline fun guard(what: String, call: () -> Unit) {
    try {
        call()
    } catch (e: RuntimeException) {
        trace("wifi lock: $what failed ${e::class.simpleName}")
    }
}
