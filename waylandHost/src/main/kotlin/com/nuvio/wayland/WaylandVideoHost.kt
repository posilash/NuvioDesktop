package com.nuvio.wayland

import com.nuvio.app.features.player.desktop.WaylandVideoBridge
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Backs [WaylandVideoBridge] with the host's mpv instance.
 *
 * Everything is expressed as mpv properties and commands, so this stays thin.
 * The one piece of state worth keeping is [hasFile]: the render loop must not
 * paint mpv's output when nothing is loaded, or an idle mpv would blank the
 * framebuffer under the UI every frame.
 */
class WaylandVideoHost(
    private val mpv: Mpv,
    private val context: DirectContext,
) : WaylandVideoBridge.Delegate {

    // Offscreen target. mpv renders here rather than into the window, so the
    // frame can be drawn as ordinary Compose content instead of sitting
    // underneath the scene where any opaque background would cover it.
    private var fbo = 0
    private var texture = 0
    private var texWidth = 0
    private var texHeight = 0
    private var renderTarget: BackendRenderTarget? = null
    private var videoSurface: Surface? = null

    private var renderCount = 0L
    private var drawCount = 0L
    private var updatePolls = 0L
    private var earlyCount = 0L
    private var lastReport = 0L

    // Rolling estimate of the host's present interval, used to decide whether a
    // frame is close enough to its deadline to draw now. Measured rather than
    // assumed: the display's refresh rate is not ours to know from here, and
    // vsync is what actually sets the loop's cadence.
    private var lastFrameNanos = 0L
    private var presentIntervalNs = 16_667_000.0

    /** Per-second summary of what the video path is actually doing. */
    fun report(now: Long): String? {
        if (now - lastReport < 1_000_000_000L) return null
        lastReport = now
        val r = renderCount; val d = drawCount; val u = updatePolls; val e = earlyCount
        renderCount = 0; drawCount = 0; updatePolls = 0; earlyCount = 0
        return "video: hasFile=$hasFile target=${texWidth}x$texHeight " +
            "mpvRenders/s=$r composeDraws/s=$d updatePolls/s=$u tooEarly/s=$e " +
            "present=%.1fms ".format(presentIntervalNs / 1e6) +
            "lastUpdateFlags=${mpv.lastUpdateFlags} surface=${videoSurface != null}"
    }

    /**
     * Render one frame, if mpv has one that is due. Must run on the GL thread.
     *
     * Returns true when the video texture changed, which is what tells the host
     * the window needs repainting even if Compose has nothing new to say.
     */
    fun renderFrame(width: Int, height: Int): Boolean {
        if (!hasFile || width <= 0 || height <= 0) return false
        ensureTarget(width, height)

        trackPresentInterval()

        // Only clear and render when mpv actually has a frame: clearing first
        // and then rendering nothing paints the window black.
        updatePolls++
        if (!mpv.hasNewFrame()) return false

        // mpv makes frames available early -- "video-timing-offset" of headroom,
        // 50ms by default -- and would normally sleep off the difference inside
        // render(). Rendering it the moment it appears would run the video fast
        // and judder; blocking would pin the UI to the video's frame rate. So
        // hold the frame until it is within half a present interval of its
        // deadline, and leave the previous one on screen until then. The update
        // flag stays set meanwhile, so the frame is not lost.
        val info = mpv.nextFrameInfo()
        if (info != null && info.isPresent && info.targetTimeNs != 0L) {
            val aheadNs = info.targetTimeNs - mpv.timeNs()
            if (aheadNs > presentIntervalNs / 2) {
                earlyCount++
                return false
            }
        }
        renderCount++

        // Skia's snapshots are copy-on-write, invalidated by Skia's own draw
        // calls. mpv writes into this FBO through raw GL, which Skia never
        // sees, so without this it keeps handing back the first snapshot it
        // took and the picture freezes -- refreshing only when a resize
        // rebuilds the surface. This is the API for exactly that case.
        videoSurface?.notifyContentWillChange(ContentChangeMode.DISCARD)

        // Start from opaque black: mpv letterboxes rather than filling, and a
        // stale frame would otherwise show through the bars.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)

        mpv.render(fbo, width, height)

        // mpv restores the framebuffer binding to 0 itself, but not the scissor
        // or viewport, and Skia's cached GL state is stale either way.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glViewport(0, 0, width, height)
        context.resetGLAll()
        return true
    }

    /**
     * Keep a smoothed estimate of how often this loop presents.
     *
     * Long gaps (a stall, a resize, the first frame) would otherwise poison the
     * average and make everything look "due", so they are ignored rather than
     * averaged in.
     */
    private fun trackPresentInterval() {
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        if (previous == 0L) return
        val deltaNs = (now - previous).toDouble()
        if (deltaNs <= 0 || deltaNs > 100_000_000) return
        presentIntervalNs += (deltaNs - presentIntervalNs) * 0.1
    }

    private fun ensureTarget(width: Int, height: Int) {
        if (fbo != 0 && width == texWidth && height == texHeight) return
        releaseTarget()

        texture = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?,
        )
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)

        fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, texture, 0,
        )
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

        texWidth = width
        texHeight = height

        // A Skia surface over the same FBO, rather than an adopted texture.
        // Image.adoptTextureFrom() hands Skia ownership of an image it treats
        // as immutable, so it never re-read what mpv wrote: the picture only
        // refreshed when a resize rebuilt it. Snapshotting a surface each frame
        // is copy-on-write and always current.
        //
        // TOP_LEFT, not BOTTOM_LEFT: rendering into an FBO with flip_y=0 leaves
        // mpv's output top-row-first. Declaring it bottom-first is what turned
        // the picture upside down.
        renderTarget = BackendRenderTarget.makeGL(
            width, height, 0, 8, fbo, FramebufferFormat.GR_GL_RGBA8,
        )
        videoSurface = Surface.makeFromBackendRenderTarget(
            context, renderTarget!!, SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        )
    }

    private fun releaseTarget() {
        videoSurface?.close(); videoSurface = null
        renderTarget?.close(); renderTarget = null
        if (fbo != 0) { GL30.glDeleteFramebuffers(fbo); fbo = 0 }
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0 }
        texWidth = 0; texHeight = 0
    }

    override fun drawVideo(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float) {
        drawCount++
        val snapshot = videoSurface?.makeImageSnapshot() ?: return
        canvas.drawImageRect(
            snapshot,
            Rect.makeWH(texWidth.toFloat(), texHeight.toFloat()),
            Rect.makeWH(width, height),
            SamplingMode.LINEAR,
            null,
            true,
        )
        snapshot.close()
    }

    @Volatile
    var hasFile: Boolean = false
        private set

    /** For the demo path, where the file is loaded directly rather than via open(). */
    fun markLoaded() { hasFile = true }

    override fun open(
        url: String,
        headers: List<String>,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        if (headers.isNotEmpty()) {
            // mpv wants header lines joined by newlines, same as the existing
            // desktop bridge passes them.
            mpv.setProperty("http-header-fields", headers.joinToString("\n"))
        }
        mpv.setProperty("pause", if (playWhenReady) "no" else "yes")
        if (startPositionMs > 0) {
            mpv.setProperty("start", (startPositionMs / 1000.0).toString())
        }
        mpv.command("loadfile", url)
        hasFile = true
    }

    override fun play() = mpv.setProperty("pause", "no")
    override fun pause() = mpv.setProperty("pause", "yes")
    override fun setSpeed(speed: Float) = mpv.setProperty("speed", speed.toString())
    override fun setMuted(muted: Boolean) = mpv.setProperty("mute", if (muted) "yes" else "no")

    override fun seekTo(positionMs: Long) {
        mpv.command("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    override fun seekBy(offsetMs: Long) {
        mpv.command("seek", (offsetMs / 1000.0).toString(), "relative")
    }

    override fun setSubtitleUrl(url: String) {
        mpv.command("sub-add", url, "select")
    }

    override fun clearExternalSubtitles() {
        mpv.command("sub-remove")
    }

    override fun selectAudioTrack(id: Int) {
        mpv.setProperty("aid", if (id < 0) "no" else id.toString())
    }

    override fun selectSubtitleTrack(id: Int) {
        mpv.setProperty("sid", if (id < 0) "no" else id.toString())
    }

    override fun stop() {
        hasFile = false
        mpv.command("stop")
    }

    override fun snapshot(): WaylandVideoBridge.Delegate.State {
        if (!hasFile) return WaylandVideoBridge.Delegate.State()
        val position = mpv.getDouble("time-pos") ?: 0.0
        val duration = mpv.getDouble("duration") ?: 0.0
        // demuxer-cache-time is an absolute timestamp, not a length, which is
        // exactly what a buffered *position* wants.
        val buffered = mpv.getDouble("demuxer-cache-time") ?: position
        val paused = mpv.getBoolean("pause") ?: false
        val idle = mpv.getBoolean("idle-active") ?: false
        val seeking = mpv.getBoolean("seeking") ?: false
        val bufferingProperty = mpv.getBoolean("paused-for-cache") ?: false

        return WaylandVideoBridge.Delegate.State(
            positionMs = (position * 1000).toLong(),
            durationMs = (duration * 1000).toLong(),
            bufferedMs = (buffered * 1000).toLong(),
            isPlaying = !paused && !idle,
            isBuffering = bufferingProperty || seeking || (duration <= 0.0 && !idle),
            hasEnded = mpv.getBoolean("eof-reached") ?: false,
        )
    }
}
