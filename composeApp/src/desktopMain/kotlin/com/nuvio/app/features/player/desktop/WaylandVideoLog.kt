package com.nuvio.app.features.player.desktop

/**
 * Diagnostics for the AWT-free video path.
 *
 * Enabled with -Dnuvio.wayland.videoLog=true. Off by default and free when off.
 * Exists because this path spans Compose, Skia and mpv, and guessing which of
 * the three is misbehaving from what the screen looks like does not work.
 */
object WaylandVideoLog {
    val enabled: Boolean by lazy {
        System.getProperty("nuvio.wayland.videoLog")?.toBoolean() ?: false
    }

    private var draws = 0L
    private var lastW = 0f
    private var lastH = 0f
    private var lastReport = 0L

    fun log(message: String) {
        if (enabled) println("[wayland-video] $message")
    }

    /** Records that the surface composed, and reports size and rate once a second. */
    fun noteDraw(width: Float, height: Float) {
        draws++
        lastW = width
        lastH = height
        val now = System.nanoTime()
        if (now - lastReport >= 1_000_000_000L) {
            lastReport = now
            println("[wayland-video] surface: draws/s=$draws size=${lastW}x$lastH")
            draws = 0
        }
    }
}
