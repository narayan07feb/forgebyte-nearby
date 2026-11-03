package com.forgebyte.nearby.macclient

import com.forgebyte.nearby.JoinCode
import com.forgebyte.nearby.demo.playAsJoiner
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Exit code 0 when the match reached the end, 1 otherwise. */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val think = opts["--think"]?.toLongOrNull() ?: 0L // ms before each move, to make a match long enough for a Wi-Fi toggle
    val code = opts["--code"]?.let(JoinCode::parse)
    val ok = runBlocking { playAsJoiner(code, opts["--name"] ?: "Mac", think) { println("[nearby] $it") } }
    exitProcess(if (ok) 0 else 1)
}
