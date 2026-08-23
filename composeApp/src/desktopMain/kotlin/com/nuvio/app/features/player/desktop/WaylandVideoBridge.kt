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

    /** An addon-provided subtitle to attach at open time. */
    data class ExternalSubtitle(
        val url: String,
        val language: String,
        val title: String?,
    )

    interface Delegate {
        /**
         * Start playing [url]. [headers] are HTTP header lines, as mpv wants
         * them. [audioUrl] is a separate audio stream for sources that split
         * tracks; without it such streams play silent. [subtitles] are
         * attached (not selected) so the track menus can offer them.
         */
        fun open(
            url: String,
            headers: List<String>,
            startPositionMs: Long,
            playWhenReady: Boolean,
            audioUrl: String? = null,
            subtitles: List<ExternalSubtitle> = emptyList(),
        )

        fun play()
        fun pause()
        fun seekTo(positionMs: Long)
        fun seekBy(offsetMs: Long)
        fun setSpeed(speed: Float)
        fun setMuted(muted: Boolean)

        /** Player volume as a 0..1 fraction, plus mute state. */
        fun audioLevel(): com.nuvio.app.features.player.PlayerAudioLevel

        /** Set player volume as a 0..1 fraction. Does not touch mute. */
        fun setVolumeFraction(fraction: Float)
        fun setSubtitleUrl(url: String)
        fun clearExternalSubtitles()
        fun selectAudioTrack(id: Int)
        fun selectSubtitleTrack(id: Int)
        fun setSubtitleDelayMs(delayMs: Int)

        /** Map the app's resize mode onto the video's scaling behaviour. */
        fun setResizeMode(mode: com.nuvio.app.features.player.PlayerResizeMode)
        fun stop()

        /** Deliver a controls-state payload to the web chrome, if hosted. */
        fun pushControlsJson(json: String) {}

        /** Current track lists, ids matching what the select methods expect. */
        fun audioTracks(): List<com.nuvio.app.features.player.AudioTrack>
        fun subtitleTracks(): List<com.nuvio.app.features.player.SubtitleTrack>

        /** Current playback state, polled by the surface once per frame. */
        fun snapshot(): State

        /**
         * Report where the video surface sits, in scene (framebuffer) pixels.
         * Called from layout whenever the surface's bounds change; the host
         * renders and composites the video into exactly this rectangle.
         */
        fun setVideoRect(left: Float, top: Float, width: Float, height: Float)

        /**
         * Called during scene rasterization where the video surface sits.
         *
         * The host composites the actual video *under* the scene; this call's
         * job is to clear the surface's rectangle to transparent so the video
         * layer shows through, while everything composed above the surface --
         * controls, overlays, dialogs -- still stacks on top. Keeping video
         * out of the scene means a new video frame never costs a scene
         * rasterization, which is what decouples video smoothness from UI
         * complexity.
         */
        fun drawVideo(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float)

        data class State(
            val positionMs: Long = 0,
            val durationMs: Long = 0,
            val bufferedMs: Long = 0,
            val isPlaying: Boolean = false,
            val isBuffering: Boolean = false,
            val hasEnded: Boolean = false,
            val playbackSpeed: Float = 1f,
            val volumeLevel: Float? = null,
            val error: String? = null,
        )
    }

    @Volatile
    var delegate: Delegate? = null

    val isAvailable: Boolean get() = delegate != null

    /**
     * True when the host runs the stock web chrome (WPE) over the video. The
     * Compose chrome then stays hidden through the usesNativePlayerChrome
     * gate, exactly as on the stock desktop build.
     */
    @Volatile
    var webChromeActive: Boolean = false

    /** Chrome {type,value} events, routed by the player surface. */
    @Volatile
    var onChromeEvent: ((String, Double) -> Unit)? = null

    /**
     * Route the app's fullscreen action to the host's window. The stock
     * handler drives an AWT window; an AWT-free host registers its own here.
     * Returns the unregister function.
     */
    fun registerFullscreenToggle(
        handler: () -> Unit,
        isFullscreen: () -> Boolean,
    ): () -> Unit = registerDesktopAppFullscreenToggle(
        handler = { handler() },
        isFullscreen = { isFullscreen() },
    )
}
