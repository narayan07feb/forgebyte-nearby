package com.forgebyte.nearby.macclient

import com.forgebyte.nearby.JoinCode
import com.forgebyte.nearby.NearbyTrace
import com.forgebyte.nearby.demo.playAsJoiner
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Exit code 0 when the match reached the end, 1 otherwise. `--trace` (no value) also prints the library's
 * NearbyTrace lines (connect, key exchange, drop, retry), and puts the Mac clock on every line, so a run log can be
 * lined up with the phone's logcat.
 */
fun main(args: Array<String>) {
    val trace = "--trace" in args
    val opts = parseOpts(args.toList())
    val think = opts["--think"]?.toLongOrNull() ?: 0L // ms before each move, to make a match long enough for a Wi-Fi toggle
    val code = opts["--code"]?.let(JoinCode::parse)
    val out: (String) -> Unit = if (trace) { line -> println(stamped(LocalTime.now(), line)) } else ::println
    if (trace) NearbyTrace.sink = { out("[trace] $it") }
    val ok = runBlocking { playAsJoiner(code, opts["--name"] ?: "Mac", think) { out("[nearby] $it") } }
    exitProcess(if (ok) 0 else 1)
}

/** `--key value` pairs; the value-less `--trace` flag is skipped so it can stand anywhere. */
internal fun parseOpts(args: List<String>): Map<String, String> =
    (args - "--trace").chunked(2).associate { it[0] to it.getOrElse(1) { "" } }

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal fun stamped(at: LocalTime, line: String): String = "${at.format(CLOCK)} $line"
