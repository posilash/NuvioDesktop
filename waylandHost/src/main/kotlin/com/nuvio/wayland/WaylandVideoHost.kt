package com.nuvio.wayland

import com.nuvio.app.features.player.desktop.WaylandVideoBridge
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
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
    private var image: Image? = null

    /** Render one frame, if mpv has a new one. Must run on the GL thread. */
    fun renderFrame(width: Int, height: Int) {
        if (!hasFile || width <= 0 || height <= 0) return
        ensureTarget(width, height)
        if (!mpv.hasNewFrame()) return

        mpv.render(fbo, width, height)

        // mpv leaves its own framebuffer, scissor and viewport bound.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glViewport(0, 0, width, height)
        context.resetGLAll()
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
        image = Image.adoptTextureFrom(
            context,
            BackendTexture.makeGL(width, height, false, texture, GL11.GL_TEXTURE_2D, GL11.GL_RGBA8),
            SurfaceOrigin.BOTTOM_LEFT,
            ColorType.RGBA_8888,
        )
    }

    private fun releaseTarget() {
        image?.close(); image = null
        if (fbo != 0) { GL30.glDeleteFramebuffers(fbo); fbo = 0 }
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0 }
        texWidth = 0; texHeight = 0
    }

    override fun drawVideo(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float) {
        val img = image ?: return
        canvas.drawImageRect(
            img,
            Rect.makeWH(texWidth.toFloat(), texHeight.toFloat()),
            Rect.makeWH(width, height),
            SamplingMode.LINEAR,
            null,
            true,
        )
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
