package com.forgebyte.nearby

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * End-to-end encryption between each joiner and the host (owner, 2026-11-03). Every primitive comes from
 * cryptography-kotlin over the platform's own crypto: ECDH P-256, HKDF-SHA256, AES-256-GCM, SHA-256.
 *
 * Key exchange, before any game message (commit then reveal, as in Bluetooth numeric comparison and ZRTP):
 *   1. joiner -> host  Kx1: SHA-256 of the joiner's fresh public key (a commitment)
 *   2. host -> joiner  Kx2: the room's public key and 16 fresh random bytes
 *   3. joiner -> host  Kx3: the joiner's public key; the host checks it against the commitment
 * Neither side can pick its key after seeing the other's, so a man in the middle cannot grind keys until the
 * 6-digit check codes on both screens match. A joiner with the QR or typed code checks the room key's fingerprint
 * before step 3, so the code join needs no check code.
 *
 * After that every frame is AES-GCM with a key per direction and a nonce that is the frame's counter in that
 * direction. The receiver only accepts the next counter, so a replayed, dropped or reordered frame fails to
 * decrypt and the connection is closed.
 */

/** Test hook: set to collect a timeline of connects and key exchanges (no keys, names or game data in it). */
internal var traceSink: ((String) -> Unit)? = null

/** Test hook for a whole-test recorder, independent of [traceSink] that single tests set and clear. */
internal var traceRecorder: ((String) -> Unit)? = null

internal fun trace(line: String) {
    traceSink?.invoke(line)
    traceRecorder?.invoke(line)
}

/** Bump when the key exchange or frame format changes. */
internal const val CRYPTO_VERSION: Int = 1

@Serializable internal data class Kx1(val v: Int, val commit: String)

@Serializable internal data class Kx2(val v: Int, val hostKey: String, val nonce: String)

@Serializable internal data class Kx3(val key: String)

internal expect val cryptoProvider: CryptographyProvider

private val curve = EC.Curve.P256
private val label = "forgebyte-nearby/1".encodeToByteArray()

/** The room's key pair: made new for every room and never stored. */
internal class HostKey private constructor(val keyPair: ECDH.KeyPair, val publicKey: ByteArray, val fingerprint: String) {
    companion object {
        suspend fun create(): HostKey {
            val pair = cryptoProvider.get(ECDH).keyPairGenerator(curve).generateKey()
            val public = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
            return HostKey(pair, public, fingerprintOf(public))
        }
    }
}

/** 64 bits of SHA-256 of the room's public key, as 16 hex characters; carried in the QR or typed code. */
internal suspend fun fingerprintOf(publicKey: ByteArray): String = sha256(publicKey).copyOf(8).toHex()

/**
 * One connection's two directions. Not thread safe: seal from one coroutine at a time.
 *
 * Explicit nonces are the library's "delicate" API because a repeated nonce under one AES-GCM key breaks it. Here
 * each key is used in one direction only, the nonce is that direction's strictly increasing frame counter, and
 * keys are new for every connection, so a nonce never repeats under a key.
 */
@OptIn(DelicateCryptographyApi::class)
internal class SecureSession(
    sendKey: AES.GCM.Key,
    receiveKey: AES.GCM.Key,
    /** Six digits both screens show for a join request; equal on both ends only without a man in the middle. */
    val checkCode: String,
    /** The room key's fingerprint, so a joiner can insist on the same room when it reconnects. */
    val hostFingerprint: String,
) {
    private val sender = sendKey.cipher()
    private val receiver = receiveKey.cipher()
    private var sent = 0L
    private var received = 0L

    suspend fun seal(text: String): ByteArray = sender.encryptWithIv(nonce(sent++), text.encodeToByteArray())

    /** Null when the frame was changed, replayed, reordered or not meant for this key. */
    suspend fun open(frame: ByteArray): String? = try {
        receiver.decryptWithIv(nonce(received), frame).decodeToString().also { received++ }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun nonce(counter: Long) = ByteArray(12).also { for (i in 0 until 8) it[11 - i] = (counter shr (8 * i)).toByte() }
}

internal sealed interface Handshake {
    data class Ok(val session: SecureSession) : Handshake
    data object VersionMismatch : Handshake
    data object WrongHostKey : Handshake
    data object Failed : Handshake
}

/** The host's side of the key exchange. Null when the joiner broke the protocol; the caller closes. */
internal suspend fun hostHandshake(key: HostKey, receive: suspend () -> String?, send: suspend (String) -> Unit): SecureSession? {
    val kx1 = receive()?.let { decode(Kx1.serializer(), it) } ?: return null
    if (kx1.v != CRYPTO_VERSION) {
        send(encode(Kx2.serializer(), Kx2(CRYPTO_VERSION, "", ""))) // so the joiner can say "update the app"
        return null
    }
    val nonce = secureRandomBytes(16)
    send(encode(Kx2.serializer(), Kx2(CRYPTO_VERSION, b64(key.publicKey), b64(nonce))))
    val joinerKey = receive()?.let { decode(Kx3.serializer(), it) }?.key?.let(::unb64) ?: return null
    if (!sha256(joinerKey).contentEquals(unb64(kx1.commit))) return null
    val shared = sharedSecret(key.keyPair.privateKey, joinerKey) ?: return null
    return session(shared, key.publicKey, joinerKey, nonce, host = true)
}

/** The joiner's side. With [fingerprint] (from the QR or typed code) the room key must match it. */
internal suspend fun joinerHandshake(fingerprint: String?, receive: suspend () -> String?, send: suspend (String) -> Unit): Handshake {
    val pair = cryptoProvider.get(ECDH).keyPairGenerator(curve).generateKey()
    val ownKey = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
    send(encode(Kx1.serializer(), Kx1(CRYPTO_VERSION, b64(sha256(ownKey)))))
    val kx2 = receive()?.let { decode(Kx2.serializer(), it) } ?: return Handshake.Failed
    if (kx2.v != CRYPTO_VERSION) return Handshake.VersionMismatch
    val hostKey = unb64(kx2.hostKey) ?: return Handshake.Failed
    val nonce = unb64(kx2.nonce)?.takeIf { it.size == 16 } ?: return Handshake.Failed
    if (fingerprint != null && fingerprintOf(hostKey) != fingerprint.lowercase()) return Handshake.WrongHostKey
    send(encode(Kx3.serializer(), Kx3(b64(ownKey))))
    val shared = sharedSecret(pair.privateKey, hostKey) ?: return Handshake.Failed
    return Handshake.Ok(session(shared, hostKey, ownKey, nonce, host = false))
}

private suspend fun sharedSecret(own: ECDH.PrivateKey, otherRaw: ByteArray): ByteArray? = try {
    val other = cryptoProvider.get(ECDH).publicKeyDecoder(curve).decodeFromByteArray(EC.PublicKey.Format.RAW, otherRaw)
    own.sharedSecretGenerator().generateSharedSecretToByteArray(other)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null // not a point on the curve, or junk
}

private suspend fun session(shared: ByteArray, hostKey: ByteArray, joinerKey: ByteArray, nonce: ByteArray, host: Boolean): SecureSession {
    val transcript = sha256(label + hostKey + joinerKey + nonce)
    val keys = cryptoProvider.get(HKDF)
        .secretDerivation(SHA256, 512.bits, salt = transcript, info = "keys".encodeToByteArray())
        .deriveSecretToByteArray(shared)
    val aes = cryptoProvider.get(AES.GCM).keyDecoder()
    val toHost = aes.decodeFromByteArray(AES.Key.Format.RAW, keys.copyOfRange(0, 32))
    val toJoiner = aes.decodeFromByteArray(AES.Key.Format.RAW, keys.copyOfRange(32, 64))
    val check = sha256("check".encodeToByteArray() + transcript)
    val number = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (check[i].toLong() and 0xff) }
    val code = (number % 1_000_000).toString().padStart(6, '0')
    val fingerprint = fingerprintOf(hostKey)
    return if (host) SecureSession(toJoiner, toHost, code, fingerprint) else SecureSession(toHost, toJoiner, code, fingerprint)
}

private suspend fun sha256(data: ByteArray): ByteArray = cryptoProvider.get(SHA256).hasher().hash(data)

private fun <T> encode(serializer: KSerializer<T>, value: T) = NearbyProtocol.json.encodeToString(serializer, value)

private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
    try { NearbyProtocol.json.decodeFromString(serializer, text) } catch (_: IllegalArgumentException) { null }

@OptIn(ExperimentalEncodingApi::class)
private fun b64(bytes: ByteArray) = Base64.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
private fun unb64(text: String): ByteArray? = try { Base64.decode(text) } catch (_: IllegalArgumentException) { null }
