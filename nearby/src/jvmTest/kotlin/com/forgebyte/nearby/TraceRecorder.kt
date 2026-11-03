package com.forgebyte.nearby

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Collections
import kotlin.time.TimeSource

/**
 * Records every trace line of a test (with thread and ms since the test began) and prints them when the test fails,
 * so a rare network flake always comes with its timeline. Use as `@get:Rule val trace = TraceRecorder()`.
 */
class TraceRecorder : TestWatcher() {
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private var t0 = TimeSource.Monotonic.markNow()

    override fun starting(description: Description) {
        lines.clear()
        t0 = TimeSource.Monotonic.markNow()
        traceRecorder = { lines += "${t0.elapsedNow().inWholeMilliseconds} [${Thread.currentThread().name}] $it" }
    }

    override fun failed(e: Throwable, description: Description) {
        println("=== TRACE ${description.methodName}\n" + lines.toList().joinToString("\n"))
    }

    override fun finished(description: Description) {
        traceRecorder = null
    }
}

/** Test hosts listen on loopback only, so another local program's loopback port can never answer instead. */
const val LOOPBACK = "127.0.0.1"
