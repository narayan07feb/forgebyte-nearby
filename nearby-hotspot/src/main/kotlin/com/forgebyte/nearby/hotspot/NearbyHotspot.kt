package com.forgebyte.nearby.hotspot

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.forgebyte.nearby.ShortCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Make a network" (docs/LOCAL-HOTSPOT.md): the host starts an Android local-only hotspot (no internet sharing), the
 * joiners join it, and then the normal nearby join runs on that network.
 */
public object NearbyHotspot {
    /** Android 8+ can start a local-only hotspot. Hide "Make a network" when false. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    public val canHost: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** Android 10+ can join from the app. When false, show the network name and password to type in Settings. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    public val canJoinInApp: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Starts the hotspot. Call it while the app is in front, after the runtime permission: location on Android 8 to 12,
     * Nearby devices on Android 13+. Throws [HotspotException] when Android refuses (for example, the Settings
     * hotspot is on, or the permission is missing). Close the result when the room ends.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    public suspend fun start(context: Context): Hotspot {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val before = siteLocalAddresses()
        return suspendCancellableCoroutine { cont ->
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    val network = reservation.network()
                    when {
                        !cont.isActive -> reservation.close()
                        network == null -> {
                            reservation.close()
                            cont.resumeWithException(HotspotException("the hotspot started without a network name"))
                        }
                        else -> cont.resume(Hotspot(reservation, network, before))
                    }
                }

                override fun onFailed(reason: Int) {
                    if (cont.isActive) cont.resumeWithException(HotspotException(failure(reason)))
                }
            }
            try {
                wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                cont.resumeWithException(HotspotException("the location or Nearby devices permission is missing"))
            }
        }
    }

    /**
     * Joins [network] for this app only (Android shows a "Connect?" dialog) and binds the app's sockets to it, so the
     * nearby list and short code work on it. While joined, the app's internet traffic also goes there and fails: close
     * the result when nearby play ends. Throws [HotspotException] when the player declines or the network is not found.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public suspend fun join(context: Context, network: HotspotNetwork): JoinedNetwork {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(
                WifiNetworkSpecifier.Builder().setSsid(network.ssid).setWpa2Passphrase(network.password).build(),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(joined: Network) {
                    if (!cont.isActive) return
                    connectivity.bindProcessToNetwork(joined)
                    cont.resume(JoinedNetwork(connectivity, this))
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(HotspotException("declined, or network not found"))
                }
            }
            connectivity.requestNetwork(request, callback)
            cont.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(callback) } }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun WifiManager.LocalOnlyHotspotReservation.network(): HotspotNetwork? {
        val (ssid, password) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") // getSsid() is deprecated on 33 for getWifiSsid(), which is 33+ only.
            softApConfiguration.let { it.ssid to it.passphrase }
        } else {
            @Suppress("DEPRECATION") // getWifiConfiguration() is the only way below Android 11.
            wifiConfiguration?.let { it.SSID to it.preSharedKey }
        } ?: return null
        val name = ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return null
        val pass = password?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return null
        return HotspotNetwork(name, pass)
    }

    private fun failure(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "turn off the phone's own hotspot first"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "this phone does not allow a hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no free Wi-Fi channel for a hotspot"
        else -> "the hotspot did not start (reason $reason)"
    }
}

/** The running hotspot. [network] is for the QR; never log it. Close it when the room ends. */
@RequiresApi(Build.VERSION_CODES.O)
public class Hotspot internal constructor(
    private val reservation: WifiManager.LocalOnlyHotspotReservation,
    public val network: HotspotNetwork,
    private val addressesBefore: List<Pair<String, String>>,
) : AutoCloseable {
    /**
     * The room's short code on the hotspot (the host's hotspot address and [port]). Android sets that address a moment
     * after the start, so this waits up to [timeoutMs]; null if it never came.
     */
    public suspend fun shortCode(port: Int, timeoutMs: Long = 5_000): ShortCode? = withTimeoutOrNull(timeoutMs) {
        var address = pickHotspotAddress(addressesBefore, siteLocalAddresses())
        while (address == null) {
            delay(200)
            address = pickHotspotAddress(addressesBefore, siteLocalAddresses())
        }
        ShortCode.of(address, port)
    }

    /** Stops the hotspot. */
    override fun close(): Unit = reservation.close()
}

/** The joined hotspot network. Closing it unbinds the app's sockets and lets Android go back to the saved Wi-Fi. */
public class JoinedNetwork internal constructor(
    private val connectivity: ConnectivityManager,
    private val callback: ConnectivityManager.NetworkCallback,
) : AutoCloseable {
    override fun close() {
        connectivity.bindProcessToNetwork(null)
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}

/** Android refused the hotspot or the join. [message] is safe to show and never holds the password. */
public class HotspotException(message: String) : Exception(message)
