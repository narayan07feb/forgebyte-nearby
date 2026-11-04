package com.forgebyte.nearby

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Asks the platform for low-latency Wi-Fi. Both calls may repeat and never throw. */
internal interface WifiLatencyLock {
    fun acquire()
    fun release()
}

/** Android: a Wi-Fi lock (low latency on API 29+, high performance below). Elsewhere: nothing to do. */
internal expect fun newWifiLatencyLock(): WifiLatencyLock

/**
 * Holds the lock only while [phases] says a match is running, and releases it when the match ends, the room closes or
 * this coroutine is cancelled (leave, end of the connection, the game's scope going away). The lock is made on the
 * first match, so a room that never starts asks the platform for nothing.
 */
internal suspend fun holdWifiDuringMatch(phases: Flow<Phase?>, newLock: () -> WifiLatencyLock) {
    var lock: WifiLatencyLock? = null
    try {
        phases.map { it == Phase.PLAYING }.distinctUntilChanged().collect { playing ->
            if (playing) (lock ?: newLock().also { lock = it }).acquire() else lock?.release()
        }
    } finally {
        lock?.release()
    }
}
