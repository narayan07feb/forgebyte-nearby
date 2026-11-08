package com.forgebyte.nearby.testing

import com.forgebyte.nearby.NearbyGame
import com.forgebyte.nearby.NearbyHost
import com.forgebyte.nearby.PROTOCOL_VERSION
import com.forgebyte.nearby.RoomAd
import com.forgebyte.nearby.RoomConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A host on the same phone, for debug builds: a real [NearbyHost] on 127.0.0.1 that accepts every join request,
 * starts as soon as [NearbyHost.canStart] and plays seat 0 with [script] (null: nothing to do now). Pass [open]'s
 * [RoomAd] to the game's own `NearbyClient.requestJoin` to try the joiner screens with one phone.
 *
 * Debug only: add `nearby-testing` with `debugImplementation`, never `implementation` (docs/ADAPTERS.md).
 */
public class FakeHost<S, M, V>(
    private val game: NearbyGame<S, M, V>,
    private val config: RoomConfig,
    private val scope: CoroutineScope,
    private val moveDelay: Duration = 600.milliseconds,
    private val script: (V) -> M?,
) {
    public val host: NearbyHost<S, M, V> = NearbyHost(game, config, scope)
    private val jobs = mutableListOf<Job>()

    /** Opens the room on loopback (other phones cannot reach it) and returns what the game's joiner taps. */
    public suspend fun open(): RoomAd {
        val port = host.open(bindHost = LOOPBACK)
        jobs += scope.launch { host.joinRequests.collect { requests -> requests.forEach { host.acceptJoin(it.id) } } }
        jobs += scope.launch {
            host.canStart.first { it }
            host.startGame()
        }
        jobs += scope.launch { playScripted(host.seqView, moveDelay, script) { move, seq -> host.move(move, seq) } }
        return RoomAd("fake-host", config.roomName, game.gameId, PROTOCOL_VERSION, config.gameVersion, config.maxSeats - 1, LOOPBACK, port)
    }

    /** Ends the room for its joiners, like the host leaving. */
    public suspend fun leave() {
        jobs.forEach { it.cancel() }
        host.leave()
    }
}
