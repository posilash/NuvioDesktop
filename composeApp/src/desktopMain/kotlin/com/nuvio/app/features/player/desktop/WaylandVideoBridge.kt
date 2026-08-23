package com.nuvio.app.features.player.desktop

/**
 * Seam between the app and an AWT-free host that renders video itself.
 *
 * The desktop player normally embeds mpv into a heavyweight AWT Canvas hosted
 * by `SwingPanel`. That cannot work without AWT: `SwingPanel` requires
 * `LocalInteropContainer`, which only Compose's AWT-backed scene provides, so
 * on a bare `ComposeScene` it fails with "LocalInteropContainer not provided".
 *
 * When a host renders video through the libmpv render API into the same GL
 * context Compose draws into, there is no component to embed at all: the video
 * is simply painted underneath the UI. Such a host installs a [Delegate] here,
 * and `PlatformPlayerSurface` routes playback to it instead of to `SwingPanel`.
 *
 * Left null in the normal AWT build, where nothing changes.
 */
object WaylandVideoBridge {

    interface Delegate {
        /** Start playing [url]. [headers] are HTTP header lines, as mpv wants them. */
        fun open(url: String, headers: List<String>, startPositionMs: Long, playWhenReady: Boolean)

        fun play()
        fun pause()
        fun seekTo(positionMs: Long)
        fun seekBy(offsetMs: Long)
        fun setSpeed(speed: Float)
        fun setMuted(muted: Boolean)
        fun setSubtitleUrl(url: String)
        fun clearExternalSubtitles()
        fun selectAudioTrack(id: Int)
        fun selectSubtitleTrack(id: Int)
        fun stop()

        /** Current playback state, polled by the surface once per frame. */
        fun snapshot(): State

        /**
         * Draw the latest video frame into [canvas] at the given rectangle.
         *
         * The host renders video into an offscreen texture, so the frame is
         * drawn *inside* the Compose scene rather than underneath it. That is
         * what makes ordering, clipping and position behave: painted beneath
         * the scene, any opaque background above it would simply cover it.
         *
         * Called on the render thread, during composition.
         */
        fun drawVideo(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float)

        data class State(
            val positionMs: Long = 0,
            val durationMs: Long = 0,
            val bufferedMs: Long = 0,
            val isPlaying: Boolean = false,
            val isBuffering: Boolean = false,
            val hasEnded: Boolean = false,
            val error: String? = null,
        )
    }

    @Volatile
    var delegate: Delegate? = null

    val isAvailable: Boolean get() = delegate != null
}
