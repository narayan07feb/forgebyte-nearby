package com.forgebyte.nearby.testing

import com.forgebyte.nearby.ClientStatus
import com.forgebyte.nearby.NearbyClient
import com.forgebyte.nearby.NearbyHost
import com.forgebyte.nearby.PROTOCOL_VERSION
import com.forgebyte.nearby.RoomAd
import com.forgebyte.nearby.SeqView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A second player on the same phone, for debug builds: a real [NearbyClient] that joins this app's own
 * [NearbyHost] on 127.0.0.1, says ready and plays the move [script] picks for each view (null: nothing to do now).
 * The host side sees a normal joiner, so the game's lobby, approval, turn and result screens all run for real.
 *
 * Debug only: add `nearby-testing` with `debugImplementation`, never `implementation` (docs/ADAPTERS.md).
 */
public class FakePeer<M, V>(
    private val gameId: String,
    private val gameVersion: Int,
    moveSerializer: KSerializer<M>,
    viewSerializer: KSerializer<V>,
    private val scope: CoroutineScope,
    private val name: String = "Test player",
    /** Wait this long before each move, so a person can follow the game. */
    private val moveDelay: Duration = 600.milliseconds,
    private val script: (V) -> M?,
) {
    public val client: NearbyClient<M, V> = NearbyClient(gameId, gameVersion, moveSerializer, viewSerializer, scope)

    /**
     * Asks to join [host], which listens on [port] (from [NearbyHost.open]). With [autoAccept] the peer accepts its
     * own request (matched by check code); without it the request waits in [NearbyHost.joinRequests] for the
     * game's approve button. The job ends when the join ends; cancel it or call [leave] to stop the peer.
     */
    public fun join(host: NearbyHost<*, M, V>, port: Int, autoAccept: Boolean = true): Job = scope.launch {
        client.requestJoin(RoomAd("fake-peer", name, gameId, PROTOCOL_VERSION, gameVersion, 1, LOOPBACK, port), name)
        if (autoAccept) launch {
            val code = client.checkCode.filterNotNull().first()
            host.acceptJoin(host.joinRequests.first { r -> r.any { it.checkCode == code } }.first { it.checkCode == code }.id)
        }
        launch {
            client.status.first { it == ClientStatus.Connected }
            client.ready(true)
            playScripted(client.seqView, moveDelay, script) { move, seq -> client.move(move, seq) }
        }
        client.status.first { it is ClientStatus.Ended }
        cancel() // also stops a pending accept or a move not yet sent
    }

    public suspend fun leave(): Unit = client.leave()
}

internal const val LOOPBACK = "127.0.0.1"

/** Plays [script]'s move for each new view after [moveDelay]; a newer view cancels a move not yet sent. */
internal suspend fun <M, V> playScripted(
    views: StateFlow<SeqView<V>?>, moveDelay: Duration, script: (V) -> M?, send: suspend (M, Int) -> Unit,
) {
    views.filterNotNull().collectLatest { v ->
        val move = script(v.view) ?: return@collectLatest
        delay(moveDelay)
        send(move, v.seq)
    }
}
