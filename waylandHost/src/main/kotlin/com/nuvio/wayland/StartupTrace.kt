package com.nuvio.wayland

/**
 * The frames between clicking a stream and the loading page, on one clock.
 *
 * Everything about a session start is spread across four threads -- the app
 * commits on the Compose dispatcher, WPE renders on the GLib thread, the layer
 * adopts on the UI thread, the window presents on the EDT -- so "there is
 * still a flash" is otherwise unattributable. Each stage marks itself here
 * with a millisecond offset from open(), and every PRESENT reports what it
 * actually put on screen.
 *
 * Identical presents collapse into one line with a count, so a flash reads as
 * what it is -- `present video=no chrome=off x7` is seven blank frames -- and
 * the eye does not have to count hundreds of lines to find it.
 *
 * The window opens at open() and shuts shortly after the first video frame,
 * so this costs nothing during playback. Only under
 * -Pnuvio.wayland.videoLog=true.
 */
object StartupTrace {

    private val on = System.getProperty("nuvio.wayland.videoLog")?.toBoolean() ?: false

    /** Backstop, for a session that never reaches its first frame. */
    private const val WINDOW_NS = 8_000_000_000L

    @Volatile private var t0 = 0L
    @Volatile private var until = 0L

    /**
     * Publishes from before the window opened. The screen goes blank when the
     * app swaps to the player screen, which happens BEFORE open() and would
     * otherwise be invisible -- yet it is part of the same flash.
     */
    private const val HISTORY = 12
    private val historyNs = LongArray(HISTORY)
    private val historyChrome = BooleanArray(HISTORY)
    private var historyAt = 0

    // Present coalescing.
    private var lastPresent: String? = null
    private var presentCount = 0
    private var presentFirstNs = 0L

    val active: Boolean get() = on && System.nanoTime() < until

    @Synchronized
    fun begin() {
        if (!on) return
        val now = System.nanoTime()
        flushPresents()
        t0 = now
        until = now + WINDOW_NS
        println("[startup] ---- session start ----")
        val first = maxOf(0, historyAt - HISTORY)
        for (i in first until historyAt) {
            val slot = i % HISTORY
            println(
                "[startup] %8.1fms ui publish (before open) chrome=%s"
                    .format((historyNs[slot] - now) / 1e6, if (historyChrome[slot]) "on" else "off"),
            )
        }
        say("open")
    }

    /** Keep tracing for [ms] more, then stop: playback is under way. */
    @Synchronized
    fun endAfter(ms: Long) {
        if (!on || until == 0L) return
        until = minOf(until, System.nanoTime() + ms * 1_000_000L)
    }

    @Synchronized
    fun mark(what: String) {
        if (!active) return
        flushPresents()
        say(what)
    }

    /**
     * One present. [what] is the signature of what reached the screen;
     * consecutive presents with the same signature are counted, not printed.
     */
    @Synchronized
    fun present(what: String) {
        if (!active) return
        if (what == lastPresent) {
            presentCount++
            return
        }
        flushPresents()
        lastPresent = what
        presentCount = 1
        presentFirstNs = System.nanoTime()
    }

    /** Called for every published UI frame, in or out of the window. */
    @Synchronized
    fun publish(chromeComposited: Boolean, generation: Int) {
        if (!on) return
        val now = System.nanoTime()
        val slot = historyAt % HISTORY
        historyNs[slot] = now
        historyChrome[slot] = chromeComposited
        historyAt++
        if (now < until) {
            flushPresents()
            say("ui publish gen=$generation chrome=${if (chromeComposited) "on" else "off"}")
        }
    }

    private fun flushPresents() {
        val sig = lastPresent ?: return
        val span = (System.nanoTime() - presentFirstNs) / 1e6
        println(
            "[startup] %8.1fms present %s x%d (%.1fms)"
                .format((presentFirstNs - t0) / 1e6, sig, presentCount, span),
        )
        lastPresent = null
        presentCount = 0
    }

    private fun say(what: String) {
        println("[startup] %8.1fms %s".format((System.nanoTime() - t0) / 1e6, what))
    }
}
