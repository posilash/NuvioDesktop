package com.nuvio.wayland

import com.nuvio.app.features.player.desktop.WaylandVideoBridge
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Paint
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
 * Playback control is mpv properties and commands. The video itself flows
 * through [VideoPipeline] on its own thread; this class is the consumer side:
 * the scene punches a transparent hole where the surface sits ([drawVideo]),
 * and [compositeVideo] paints the latest published frame into that hole,
 * underneath the UI layer. The scene is never rasterized on account of a video
 * frame -- that separation is the point of the design.
 */
class WaylandVideoHost(
    private val mpv: Mpv,
    private val pipeline: VideoPipeline,
    private val context: DirectContext,
) : WaylandVideoBridge.Delegate {

    // Where the video belongs, in framebuffer pixels. Reported by the surface
    // composable from layout; the demo path sets it directly.
    @Volatile private var rectLeft = 0f
    @Volatile private var rectTop = 0f
    @Volatile private var rectWidth = 0f
    @Volatile private var rectHeight = 0f

    private var composites = 0L
    private var holePunches = 0L
    private var lastReport = 0L

    override fun setVideoRect(left: Float, top: Float, width: Float, height: Float) {
        rectLeft = left; rectTop = top; rectWidth = width; rectHeight = height
        pipeline.setTargetSize(width.toInt(), height.toInt())
    }

    /** Per-second summary of what the video path is actually doing. */
    fun report(now: Long): String? {
        if (lastReport == 0L) { lastReport = now; return null }
        if (now - lastReport < 1_000_000_000L) return null
        val elapsed = (now - lastReport) / 1e9
        lastReport = now
        val c = composites; composites = 0
        val p = holePunches; holePunches = 0
        return "video: hasFile=$hasFile rect=${rectWidth.toInt()}x${rectHeight.toInt()}" +
            "+${rectLeft.toInt()}+${rectTop.toInt()} composites/s=%.0f punches/s=%.0f | "
                .format(c / elapsed, p / elapsed) +
            pipeline.report(elapsed)
    }

    /**
     * Called during scene rasterization where the video surface sits: clears
     * that rectangle to transparent so [compositeVideo]'s output shows through
     * from the layer below, while everything the scene draws after (controls,
     * overlays, dialogs) stacks above.
     */
    override fun drawVideo(canvas: Canvas, width: Float, height: Float) {
        holePunches++
        canvas.drawRect(
            Rect.makeWH(width, height),
            Paint().apply { blendMode = BlendMode.CLEAR },
        )
    }

    // ---- Composite side (UI thread, window GL context) ----

    // Skia wrappers over the pipeline's shared textures. Framebuffers do not
    // cross contexts, so this side binds each texture into its own FBO and
    // wraps that for Skia. Keyed by buffer generation: a reallocation on the
    // video thread invalidates the wrapper.
    private class Wrapper(
        val fbo: Int,
        val renderTarget: BackendRenderTarget,
        val surface: Surface,
        val generation: Int,
    )

    private val wrappers = HashMap<Int, Wrapper>()

    /**
     * Draw the newest published video frame into the window at the reported
     * rect. Runs on the UI thread with the window context current, after the
     * background clear and before the UI layer.
     */
    fun compositeVideo(canvas: Canvas) {
        if (!hasFile) return
        if (rectWidth <= 0f || rectHeight <= 0f) return
        val frame = pipeline.acquireDisplayFrame() ?: return
        val buf = frame.buffer

        val wrapper = wrappers.getOrPut(buf.generation) {
            val fbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, buf.texture, 0,
            )
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            val rt = BackendRenderTarget.makeGL(
                buf.width, buf.height, 0, 8, fbo, FramebufferFormat.GR_GL_RGBA8,
            )
            // TOP_LEFT: mpv renders with flip_y=0 into an FBO, leaving the
            // image top-row-first.
            val surface = Surface.makeFromBackendRenderTarget(
                context, rt, SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
            ) ?: error("could not wrap video texture for Skia")
            evictStaleWrappers(keep = buf.generation)
            // The FBO creation above went through raw GL behind Skia's back;
            // its cached bindings are stale until told. Rare: once per
            // texture generation, i.e. per resize.
            context.resetGLAll()
            Wrapper(fbo, rt, surface, buf.generation)
        }

        if (pipeline.probe && frame.fresh) {
            // Consumer-side truth: what this context reads from the same
            // texture, after the fence wait. Compare with the publish line.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, wrapper.fbo)
            val px = java.nio.ByteBuffer.allocateDirect(4)
            GL11.glReadPixels(
                buf.width / 2, buf.height / 2, 1, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px,
            )
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
            val v = (px.get(0).toInt() and 0xFF shl 16) or
                (px.get(1).toInt() and 0xFF shl 8) or (px.get(2).toInt() and 0xFF)
            println(
                "[wayland-video] consume: gen=${buf.generation} tex=${buf.texture} " +
                    "center=%06x".format(v),
            )
        }
        if (frame.fresh) {
            // Two caches must be told the texture changed behind their backs.
            // Skia's copy-on-write snapshot would be the first frame forever
            // without notifyContentWillChange. And GL itself only guarantees a
            // context sees another context's writes to a shared texture after
            // *re-binding* it -- which Skia's state cache elides, since as far
            // as it knows the texture never left the unit. Dropping the cached
            // texture bindings forces the re-bind; without it, buffers
            // alternated between live frames and their initial cleared black.
            wrapper.surface.notifyContentWillChange(ContentChangeMode.DISCARD)
            context.resetGL(org.jetbrains.skia.GLBackendState.TEXTURE_BINDING)
        }
        val snapshot = wrapper.surface.makeImageSnapshot()
        canvas.drawImageRect(
            snapshot,
            Rect.makeWH(buf.width.toFloat(), buf.height.toFloat()),
            Rect.makeXYWH(rectLeft, rectTop, rectWidth, rectHeight),
            SamplingMode.LINEAR,
            null,
            true,
        )
        snapshot.close()
        composites++
    }

    private fun evictStaleWrappers(keep: Int) {
        // Generations only grow; anything older than (keep - 3) can no longer
        // be republished by the triple-buffered pipeline.
        val stale = wrappers.keys.filter { it < keep - 3 }
        for (g in stale) {
            wrappers.remove(g)?.let {
                it.surface.close()
                it.renderTarget.close()
                GL30.glDeleteFramebuffers(it.fbo)
            }
        }
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
        audioUrl: String?,
        subtitles: List<WaylandVideoBridge.ExternalSubtitle>,
    ) {
        if (headers.isNotEmpty()) {
            // mpv wants header lines joined by newlines, same as the existing
            // desktop bridge passes them.
            mpv.setProperty("http-header-fields", headers.joinToString("\n"))
        }
        // Sources that split their audio into a separate stream are silent
        // without this. Cleared explicitly: the property persists across
        // loads, and the previous file's audio bleeding into the next is
        // exactly the kind of bug nobody traces quickly.
        mpv.setProperty("audio-files", audioUrl ?: "")
        mpv.setProperty("pause", if (playWhenReady) "no" else "yes")
        if (startPositionMs > 0) {
            mpv.setProperty("start", (startPositionMs / 1000.0).toString())
        }
        mpv.command("loadfile", url)
        for (sub in subtitles) {
            // "auto" attaches without selecting; the app's own subtitle
            // policy decides what to enable.
            mpv.command(
                "sub-add", sub.url, "auto",
                sub.title ?: sub.language, sub.language,
            )
        }
        hasFile = true
    }

    override fun play() = mpv.setProperty("pause", "no")
    override fun pause() = mpv.setProperty("pause", "yes")
    override fun setSpeed(speed: Float) = mpv.setProperty("speed", speed.toString())
    override fun setMuted(muted: Boolean) = mpv.setProperty("mute", if (muted) "yes" else "no")

    override fun audioLevel(): com.nuvio.app.features.player.PlayerAudioLevel =
        com.nuvio.app.features.player.PlayerAudioLevel(
            fraction = ((mpv.getDouble("volume") ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f),
            isMuted = mpv.getBoolean("mute") ?: false,
        )

    override fun setVolumeFraction(fraction: Float) {
        mpv.setProperty("volume", (fraction.coerceIn(0f, 1f) * 100.0).toString())
    }

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

    override fun setSubtitleDelayMs(delayMs: Int) {
        mpv.setProperty("sub-delay", (delayMs / 1000.0).toString())
    }

    override fun setResizeMode(mode: com.nuvio.app.features.player.PlayerResizeMode) {
        // mpv's own scaling knobs: keepaspect governs stretch, panscan crops
        // to fill. The render target is the punched rect, so these behave
        // exactly as in standalone mpv.
        when (mode) {
            com.nuvio.app.features.player.PlayerResizeMode.Fit -> {
                mpv.setProperty("keepaspect", "yes"); mpv.setProperty("panscan", "0")
            }
            com.nuvio.app.features.player.PlayerResizeMode.Zoom -> {
                mpv.setProperty("keepaspect", "yes"); mpv.setProperty("panscan", "1")
            }
            com.nuvio.app.features.player.PlayerResizeMode.Fill,
            com.nuvio.app.features.player.PlayerResizeMode.Stretch -> {
                mpv.setProperty("keepaspect", "no"); mpv.setProperty("panscan", "0")
            }
        }
    }

    /**
     * mpv's track list, read property-by-property (`track-list/N/...`) rather
     * than parsing the JSON blob. Track ids are mpv ids, which is what the
     * aid/sid selectors above expect back.
     */
    private data class MpvTrack(
        val id: Int,
        val title: String?,
        val language: String?,
        val selected: Boolean,
        val forced: Boolean,
    )

    private fun tracks(type: String): List<MpvTrack> {
        val count = mpv.getProperty("track-list/count")?.toIntOrNull() ?: 0
        val out = ArrayList<MpvTrack>()
        for (i in 0 until count) {
            if (mpv.getProperty("track-list/$i/type") != type) continue
            val id = mpv.getProperty("track-list/$i/id")?.toIntOrNull() ?: continue
            out += MpvTrack(
                id = id,
                title = mpv.getProperty("track-list/$i/title"),
                language = mpv.getProperty("track-list/$i/lang"),
                selected = mpv.getProperty("track-list/$i/selected") == "yes",
                forced = mpv.getProperty("track-list/$i/forced") == "yes",
            )
        }
        return out
    }

    private fun MpvTrack.label(fallback: String): String =
        title ?: language ?: "$fallback $id"

    override fun audioTracks(): List<com.nuvio.app.features.player.AudioTrack> =
        tracks("audio").map { t ->
            com.nuvio.app.features.player.AudioTrack(
                index = t.id,
                id = t.id.toString(),
                label = t.label("Audio"),
                language = t.language,
                isSelected = t.selected,
            )
        }

    override fun subtitleTracks(): List<com.nuvio.app.features.player.SubtitleTrack> =
        tracks("sub").map { t ->
            com.nuvio.app.features.player.SubtitleTrack(
                index = t.id,
                id = t.id.toString(),
                label = t.label("Subtitle"),
                language = t.language,
                isSelected = t.selected,
                isForced = t.forced,
            )
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
            // The speed cycler and its label both read this back; without it
            // they see 1x forever and cycling sticks at the first step.
            playbackSpeed = (mpv.getDouble("speed") ?: 1.0).toFloat(),
        )
    }
}
