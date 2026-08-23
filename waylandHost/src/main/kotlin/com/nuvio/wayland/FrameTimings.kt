package com.nuvio.wayland

/**
 * Per-stage frame timing, reported once a second.
 *
 * A frame-rate number on its own only says the loop is slow, not which part of
 * it is. Stages are accumulated and reported as a per-frame average so the
 * expensive one is named rather than guessed at.
 */
class FrameTimings {
    private val totals = LinkedHashMap<String, Long>()
    private var frames = 0L
    private var lastReport = 0L

    fun add(stage: String, nanos: Long) {
        totals[stage] = (totals[stage] ?: 0L) + nanos
    }

    fun endFrame() {
        frames++
    }

    fun report(now: Long): String? {
        if (lastReport == 0L) {
            lastReport = now
            return null
        }
        if (now - lastReport < 1_000_000_000L) return null
        val elapsed = now - lastReport
        val n = frames.coerceAtLeast(1)
        val body = totals.entries.joinToString(" ") { (stage, total) ->
            "$stage=%.1fms".format(total / 1e6 / n)
        }
        val fps = frames * 1e9 / elapsed
        lastReport = now
        frames = 0
        totals.clear()
        return "frame: fps=%.1f %s".format(fps, body)
    }
}
