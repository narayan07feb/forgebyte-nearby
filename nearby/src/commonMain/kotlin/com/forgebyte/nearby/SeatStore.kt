package com.forgebyte.nearby

import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * What a joiner needs to take its seat back after the app was killed and opened again: where the room is, the room's
 * key fingerprint (checked before anything is sent, so another host on the same address never gets the token), and
 * the seat's reconnect token. Give it to [NearbyClient.resume].
 *
 * The token is a secret: anyone who has it can play this seat. Keep it in private app storage, out of backups and
 * device transfer.
 */
@Serializable
public data class SavedSeat(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val gameId: String,
    val name: String,
    val token: String,
    /** Wall-clock time (epoch ms) of the seat's last drop, or null while it is seated. A seat kept after Unreachable
     *  expires 10 min after this (see [NearbyClient.resume]); an older saved value without it reads as null. */
    val droppedAtMs: Long? = null,
) {
    /** One line of text for a key-value store; read it back with [parse]. */
    public fun toText(): String = NearbyProtocol.json.encodeToString(serializer(), this)

    public companion object {
        /** The seat [toText] wrote, or null for anything else (an old or damaged value). */
        public fun parse(text: String): SavedSeat? =
            runCatching { NearbyProtocol.json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * Where a [NearbyClient] keeps its [SavedSeat]. The client saves the seat on every welcome (the token changes each
 * time), saves it again with [SavedSeat.droppedAtMs] when the link drops, and clears it when the seat ends for good:
 * the player left, was kicked or refused, or the host left. After the room could not be reached it keeps the seat, as
 * the host may still hold it; [NearbyClient.resume] skips a kept seat dropped more than 10 min ago, and clears it.
 * A killed app does not clear it, so the next start can call [NearbyClient.resume].
 *
 * The default keeps it in memory only, so a killed app loses the seat (a bot plays it). To survive a kill, a game
 * passes a store backed by private app storage, and keeps that storage out of backups and device transfer: on
 * Android, exclude the file in `android:dataExtractionRules` (Android 12+) and `android:fullBackupContent` (older), so
 * a restored phone or a new phone never gets the token.
 */
public interface SeatStore {
    public fun load(): SavedSeat?
    public fun save(seat: SavedSeat)
    public fun clear()
}

/** A [SeatStore] that forgets the seat when the app process ends. */
public class InMemorySeatStore : SeatStore {
    @Volatile private var seat: SavedSeat? = null
    override fun load(): SavedSeat? = seat
    override fun save(seat: SavedSeat) { this.seat = seat }
    override fun clear() { seat = null }
}
