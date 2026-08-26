package com.nuvio.wayland

import com.nuvio.app.features.player.desktop.WaylandVideoBridge
import com.nuvio.app.features.player.desktop.WaylandVideoLog
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.ColorSpace
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
    private val pipeline: DisplayPipeline,
    private val context: DirectContext,
) : WaylandVideoBridge.Delegate {

    // Where the video belongs, in framebuffer pixels. Reported by the surface
    // composable from layout; the demo path sets it directly.
    /** Where the video belongs, in framebuffer pixels: left, top, width, height. */
    val videoRect: FloatArray get() = floatArrayOf(rectLeft, rectTop, rectWidth, rectHeight)

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
    @Volatile private var lastCompositeNs = 0L

    /** True from START_FILE until the new file's first frame is published. */
    @Volatile private var awaitingFirstFrame = false
    @Volatile private var restartSeen = false

    /**
     * Whether the last [compositeVideo] actually put a frame on the canvas.
     * Read by the startup trace, which needs to say what a present contained.
     */
    @Volatile var drewVideo = false
        private set

    fun compositeVideo(canvas: Canvas) {
        drewVideo = false
        if (!hasFile) return
        if (rectWidth <= 0f || rectHeight <= 0f) return
        val frame = pipeline.acquireFrame() ?: return
        // Across a file change the pipeline still holds the OUTGOING file's
        // last frame, and drawing it flashes the previous stream before the
        // new one appears. PLAYBACK_RESTART alone is not enough -- the core
        // resumes before the pipeline publishes -- so wait for a freshly
        // published frame as well. One acquire only: a second one would
        // report fresh=false and skip the cache invalidation below.
        if (awaitingFirstFrame) {
            if (!restartSeen || !frame.fresh) return
            awaitingFirstFrame = false
            traceSession("first frame on screen")
            StartupTrace.mark("first video frame on screen")
            // A few more frames of evidence that playback is continuous, then
            // this stops being a startup.
            StartupTrace.endAfter(400)
        }
        lastCompositeNs = System.nanoTime()

        val wrapper = wrappers.getOrPut(frame.generation) {
            val fbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, frame.texture, 0,
            )
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            val rt = BackendRenderTarget.makeGL(
                frame.width, frame.height, 0, 8, fbo, FramebufferFormat.GR_GL_RGBA8,
            )
            // TOP_LEFT: mpv renders with flip_y=0 into an FBO, leaving the
            // image top-row-first.
            val surface = Surface.makeFromBackendRenderTarget(
                context, rt, SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
            ) ?: error("could not wrap video texture for Skia")
            evictStaleWrappers(keep = frame.generation)
            // The FBO creation above went through raw GL behind Skia's back;
            // its cached bindings are stale until told. Rare: once per
            // texture generation, i.e. per resize.
            context.resetGLAll()
            Wrapper(fbo, rt, surface, frame.generation)
        }

        if (pipeline.probe && frame.fresh) {
            // Consumer-side truth: what this context reads from the same
            // texture, after the fence wait. Compare with the publish line.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, wrapper.fbo)
            val px = java.nio.ByteBuffer.allocateDirect(4)
            GL11.glReadPixels(
                frame.width / 2, frame.height / 2, 1, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px,
            )
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
            val v = (px.get(0).toInt() and 0xFF shl 16) or
                (px.get(1).toInt() and 0xFF shl 8) or (px.get(2).toInt() and 0xFF)
            println(
                "[wayland-video] consume: gen=${frame.generation} tex=${frame.texture} " +
                    "center=%06x".format(v),
            )
        }
        if (frame.fresh) {
            // Two invalidations, both mandatory, both learned the hard way:
            // notifyContentWillChange drops Skia's cached image of this
            // surface -- Surface.draw serves that cache, and mpv's writes are
            // invisible to Skia, so without the notify every present shows
            // each buffer's first frame forever (a static, flickering image).
            // Dropping the cache costs nothing: copy-on-write only copies when
            // a snapshot is *retained* across a write, and this one is not.
            // The GL reset covers the second cache: a context only sees
            // another context's writes to a shared texture after re-binding
            // it, which Skia's state tracker would otherwise elide.
            wrapper.surface.notifyContentWillChange(ContentChangeMode.DISCARD)
            if (pipeline.rendersOnConsumerThread) {
                // mpv's whole renderer just ran on this context (sampled
                // mode): Skia's entire state mirror is fiction. Stremio's
                // window()->resetOpenGLState() bracket, Skia edition.
                context.resetGLAll()
            } else {
                context.resetGL(org.jetbrains.skia.GLBackendState.TEXTURE_BINDING)
            }
        }
        // Surface.draw, not a snapshot: snapshots are copy-on-write and cost a
        // full-frame copy whenever content changes -- which for video is every
        // frame. The buffer is rendered at exactly the rect's size, so a 1:1
        // draw at the rect's origin is the general case; a resize is a frame
        // of mismatch at most.
        if (rectWidth.toInt() == frame.width && rectHeight.toInt() == frame.height) {
            wrapper.surface.draw(canvas, rectLeft.toInt(), rectTop.toInt(), null)
        } else {
            val snapshot = wrapper.surface.makeImageSnapshot()
            canvas.drawImageRect(
                snapshot,
                Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                Rect.makeXYWH(rectLeft, rectTop, rectWidth, rectHeight),
                SamplingMode.LINEAR,
                null,
                true,
            )
            snapshot.close()
        }
        composites++
        drewVideo = true
    }

    /** A frame from the Vulkan pipeline, and whether it may be drawn yet. */
    class VulkanFrame(val frame: VideoPipelineVk.DisplayFrame, val draw: Boolean)

    /**
     * The Vulkan analogue of [compositeVideo]: identical bookkeeping, no GL.
     *
     * This exists because that bookkeeping is not incidental to drawing. It is
     * what clears awaitingFirstFrame -- which is what the controls page reads
     * as "still loading", so skipping it leaves the loading wheel up for the
     * whole session -- and it is what refuses the outgoing file's last frame
     * across a stream change, which is the flash of the previous stream.
     *
     * The frame is returned either way, because the caller owes it the
     * semaphore handoff whether or not it draws it.
     */
    fun acquireVulkanFrame(vk: VideoPipelineVk): VulkanFrame? {
        if (!hasFile) return null
        if (rectWidth <= 0f || rectHeight <= 0f) return null
        val frame = vk.acquireDisplayFrame() ?: return null
        if (awaitingFirstFrame) {
            if (!restartSeen || !frame.fresh) return VulkanFrame(frame, draw = false)
            awaitingFirstFrame = false
            traceSession("first frame on screen")
            StartupTrace.mark("first video frame on screen")
            StartupTrace.endAfter(400)
        }
        lastCompositeNs = System.nanoTime()
        composites++
        drewVideo = true
        return VulkanFrame(frame, draw = true)
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

    // ---- web chrome (stock controls.html in WPE) ----

    @Volatile var chrome: com.nuvio.wayland.wpe.WpeChrome? = null
    @Volatile private var lastControlsJson: String? = null

    /** JS string literal, matching the stock bridge's jsLiteral escaping. */
    private fun jsLiteral(s: String): String = buildString {
        append('\u0022')
        for (c in s) when (c) {
            '\u0022' -> append("\\\u0022")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
        append('\u0022')
    }

    override fun pushControlsJson(json: String) {
        lastControlsJson = json
        val c = chrome ?: return
        // This is the push that turns a bootstrap page into this session's
        // opening overlay -- artwork, title, the lot -- so it is what the
        // reveal waits for. Armed BEFORE the script goes out, so no frame
        // painted from it can slip past the count. Arming is once per
        // session; the structural re-pushes during playback leave the gate
        // alone.
        if (hasFile) {
            c.armReveal()
            traceSession("controls push")
            StartupTrace.mark("controls push ${openingDigest(json)}")
        }
        // Stock's flush script verbatim: probes window.playerControls so a
        // too-early push is detected; controlsReady re-pushes (see onMessage
        // wiring in Main).
        c.evaluateJs(
            "(function(){if(!window.playerControls)return 'missing';" +
                "window.playerControls(JSON.parse(" + jsLiteral(json) + "));" +
                "return 'ok';})()",
        )
    }

    /**
     * What a controls payload would actually put on the opening overlay.
     *
     * A push is not a push: the app sends several as a session starts, and if
     * the early ones carry no title and no artwork then revealing on the frame
     * painted from one of them shows an empty overlay -- which is a flash in
     * its own right, before the real loading screen arrives. This says which
     * push is which, in the trace, without dumping a kilobyte of JSON.
     */
    private fun openingDigest(json: String): String {
        fun has(field: String): Boolean =
            Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
                ?.isNotBlank() == true
        val overlay = Regex("\"showOpeningOverlay\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.get(1) ?: "?"
        return "overlay=$overlay title=${has("openingTitle")} " +
            "artwork=${has("openingArtwork")} logo=${has("openingLogo")} " +
            "bytes=${json.length}"
    }

    /** Re-deliver pending state; called when the page reports controlsReady. */
    fun flushControlsToChrome() {
        traceSession("push controls json (isLoading=${isLoadingNow()})")
        // Only a LIVE session consumes the page. The warm-up flush that
        // follows a reload (controlsReady with no file open) must leave it
        // pristine, or the next open() sees a used page, reloads again, and
        // that reload gap is a black flash before the opening overlay.
        if (hasFile) chrome?.markPageUsed()
        lastControlsJson?.let { pushControlsJson(it) }
    }

    /**
     * The periodic playback push the stock bridge does at its tick: position,
     * duration, pause, loading and both track lists, into
     * window.playerUpdate. Position and flags come from the observed-property
     * cache (never the core); the track lists are the only core reads, so
     * they are cached and refreshed sparsely.
     */
    private var updatePushes = 0L
    private var cachedTracksJson: String = "[],"
    private var tracksRefreshedAtNs = 0L

    /**
     * Is playback still loading, i.e. should the page keep its opening
     * overlay up and its controls hidden?
     *
     * Frames on glass are the ground truth, not mpv's flags: `seeking` stays
     * up through the initial start-position seek for a second or so after
     * playback is visibly running (which used to leave the overlay over live
     * video), and before the first frame `duration` is not known yet. Both
     * the Compose snapshot and the page's periodic update read this, so the
     * two can never disagree -- they did, and the page (told "not loading"
     * before any frame existed) dismissed its overlay and showed the
     * controls before the video appeared.
     */
    /**
     * Upstream's rawLoadingWithPaused (macos/player_bridge.mm), copied:
     *
     *   !fileReady || (core-idle && !paused && !eof) || paused-for-cache
     *
     * `core-idle` (the core is producing no frames) is the load-bearing
     * property -- not `idle-active` -- and `fileReady` is duration OR a
     * track list, so the whole file-open phase reports loading and the
     * page keeps its opening overlay up instead of revealing black.
     */
    private fun isLoadingNow(): Boolean {
        // Upstream's playerLoading(): `!firstFrameShown || computeLoading()`.
        // The latch keeps the opening overlay up for the WHOLE open --
        // through mpv's flag flicker and the resume seek -- because the page
        // dismisses that overlay the first time isLoading goes false and
        // never brings it back. awaitingFirstFrame is our firstFrameShown,
        // inverted: it clears when a frame of this file is actually drawn.
        if (awaitingFirstFrame) return true
        val paused = mpv.cachedBoolean("pause") ?: false
        val eof = mpv.cachedBoolean("eof-reached") ?: false
        // Fallback YES, as upstream: unknown means the core is not running.
        val coreIdle = mpv.cachedBoolean("core-idle") ?: true
        val bufferingCache = mpv.cachedBoolean("paused-for-cache") ?: false
        val duration = mpv.cachedDouble("duration") ?: 0.0
        val trackCount = mpv.cachedString("track-list")?.let { topLevelObjects(it).size } ?: 0
        val fileReady = duration > 0.0 || trackCount > 0
        return !fileReady || (coreIdle && !paused && !eof) || bufferingCache
    }

    /** Pause/buffer state from the observed cache -- never a core poll. */
    fun isPausedOrLoading(): Boolean =
        (mpv.cachedBoolean("pause") ?: false) ||
            (mpv.cachedBoolean("paused-for-cache") ?: false) ||
            (mpv.cachedBoolean("seeking") ?: false)

    /** Which of the three, for the session log. "The chrome came back" is one
     *  of them going true for a moment and they are not interchangeable. */
    fun pauseReason(): String =
        "pause=${mpv.cachedBoolean("pause")} " +
            "cache=${mpv.cachedBoolean("paused-for-cache")} " +
            "seek=${mpv.cachedBoolean("seeking")}"

    fun pushPlaybackUpdate() {
        val c = chrome ?: return
        if (!hasFile) return
        val now = System.nanoTime()
        if (now - tracksRefreshedAtNs > 3_000_000_000L) {
            tracksRefreshedAtNs = now
            cachedTracksJson = tracksJson("audio") + "," + tracksJson("sub")
        }
        val duration = mpv.cachedDouble("duration") ?: 0.0
        val position = mpv.cachedDouble("time-pos") ?: 0.0
        val paused = mpv.cachedBoolean("pause") ?: false
        val loading = isLoadingNow()
        val (audio, subs) = cachedTracksJson.split("],[").let {
            if (it.size == 2) Pair(it[0] + "]", "[" + it[1]) else Pair("[]", "[]")
        }
        val script =
            "window.playerUpdate&&window.playerUpdate({duration:%.3f,position:%.3f,paused:%s,loading:%s,audioTracks:%s,subtitleTracks:%s})"
                .format(java.util.Locale.ROOT, duration, position, paused, loading, audio, subs.removeSuffix(","))
        if (updatePushes++ % 10 == 0L &&
            System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true
        ) {
            println("[wpe] playerUpdate #$updatePushes: ${script.take(140)}")
        }
        c.evaluateJs(script)
    }

    private fun tracksJson(type: String): String = buildString {
        append('[')
        tracks(type).forEachIndexed { position, t ->
            if (position > 0) append(',')
            append("{\u0022index\u0022:").append(position)
            append(",\u0022id\u0022:\u0022").append(t.id).append('\u0022')
            append(",\u0022label\u0022:\u0022")
            append(t.label(if (type == "sub") "Subtitle" else "Track").replace("\u0022", "\\\u0022"))
            append('\u0022')
            append(",\u0022language\u0022:\u0022")
            append(t.language.orEmpty().replace("\u0022", "\\\u0022"))
            append('\u0022')
            append(",\u0022selected\u0022:").append(t.selected)
            append(",\u0022forced\u0022:").append(t.forced)
            append('}')
        }
        append(']')
    }

    @Volatile
    var hasFile: Boolean = false
        private set

    /** For the demo path, where the file is loaded directly rather than via open(). */
    fun markLoaded() { hasFile = true }

    init {
        mpv.onStartFile = {
            awaitingFirstFrame = true
            restartSeen = false
            traceSession("START_FILE")
        }
        mpv.onPlaybackRestart = {
            restartSeen = true
            // mpv sends this once BOTH audio and video are ready to run, so
            // it is the reference point for "when should sound start".
            traceSession("PLAYBACK_RESTART")
        }
    }


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
        // without this. Cleared with change-list, never with an empty string:
        // audio-files="" is a list holding one empty filename, which mpv then
        // tries to open ("Can not open external file .") and lets the phantom
        // external track shadow the real audio -- observed as a whole session
        // with no sound.
        if (audioUrl != null) {
            mpv.setProperty("audio-files", audioUrl)
        } else {
            mpv.command("change-list", "audio-files", "clr", "")
        }
        sessionStartNs = System.nanoTime()
        StartupTrace.begin()
        traceSession("open(playWhenReady=$playWhenReady)")
        // Shut the reveal gate before anything can look at hasFile, or the
        // gate left open by the last session shows the chrome the instant
        // this one starts -- which is the flash of stale/empty chrome. From
        // here until the reveal the layer takes the page's frames without
        // drawing them, so the overlay is ready the moment it is allowed on
        // screen.
        chrome?.let {
            it.closeReveal()
            it.priming = true
        }
        mpv.setProperty("pause", if (playWhenReady) "no" else "yes")
        if (startPositionMs > 0) {
            mpv.setProperty("start", (startPositionMs / 1000.0).toString())
        }
        mpv.command("loadfile", url)
        for (sub in subtitles.filter { it.url.isNotBlank() }) {
            // "auto" attaches without selecting; the app's own subtitle
            // policy decides what to enable.
            mpv.command(
                "sub-add", sub.url, "auto",
                sub.title ?: sub.language, sub.language,
            )
        }
        hasFile = true
        // Source switches can open a new file without a stop() in between.
        chrome?.let { if (!it.pageFresh) it.reloadPage() }
    }

    private fun traceSession(what: String) {
        if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
            println("[session] +%.2fs %s (hasFile=%s awaitingFirstFrame=%s loading=%s)"
                .format((System.nanoTime() - sessionStartNs) / 1e9, what,
                    hasFile, awaitingFirstFrame, isLoadingNow()))
        }
    }
    @Volatile private var sessionStartNs = System.nanoTime()

    override fun play() { traceSession("play()"); mpv.setProperty("pause", "no") }
    override fun pause() { traceSession("pause()"); mpv.setProperty("pause", "yes") }
    override fun setSpeed(speed: Float) = mpv.setProperty("speed", speed.toString())
    override fun setMuted(muted: Boolean) = mpv.setProperty("mute", if (muted) "yes" else "no")

    override fun audioLevel(): com.nuvio.app.features.player.PlayerAudioLevel =
        com.nuvio.app.features.player.PlayerAudioLevel(
            fraction = ((mpv.cachedDouble("volume") ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f),
            isMuted = mpv.cachedBoolean("mute") ?: false,
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
        if (url.isBlank()) return
        WaylandVideoLog.log("sub-add select url=$url")
        mpv.command("sub-add", url, "select")
    }

    override fun clearExternalSubtitles() {
        mpv.command("sub-remove")
    }

    // Same semantics as the stock JNI bridge: the id here is the MPV id
    // (already resolved from a list position by the controller); negative
    // means off. Position->id resolution lives in the controller, exactly
    // where stock puts it.
    /**
     * Selecting a track is never free, and selecting the one that is ALREADY
     * selected is pure loss: mpv refreshes the demuxer's track, reopens the
     * decoder and reinitialises the audio output for no change at all.
     *
     * Worse, a REAL switch after playback has begun costs seconds of silence,
     * because an unselected track is never demuxed -- mpv has no packets for
     * it anywhere before the demuxer's read head and plays silence until the
     * clock gets there:
     *
     *   delaying audio start 1029.376000 vs. 1024.148000, diff=5.228000
     *
     * A seek does not rescue that; the demuxer cache holds packets, not bytes,
     * and the ones for a stream that was not selected were dropped as they
     * were parsed. The only cure is to choose the track before playback
     * starts, which is why the app now applies its preference as soon as the
     * tracks exist rather than when loading ends.
     */
    override fun selectAudioTrack(id: Int) {
        val want = if (id < 0) "no" else id.toString()
        if (mpv.cachedString("aid") == want) return
        mpv.setProperty("aid", want)
    }

    override fun selectSubtitleTrack(id: Int) {
        val want = if (id < 0) "no" else id.toString()
        if (mpv.cachedString("sid") == want) return
        mpv.setProperty("sid", want)
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
     * mpv's track list, parsed from the OBSERVED "track-list" JSON in the
     * property cache. The old shape -- 4-5 getProperty core calls per track,
     * re-run every 3 seconds on the UI thread -- was a burst of synchronous
     * core polls during playback, and core polls contend with the pacing
     * render (the measured 66ms-present-gap trap). A many-track remux made
     * that burst 50+ calls: a visible playback hitch every 3 seconds, in
     * every chrome architecture, which is exactly why no amount of chrome
     * rework ever fixed "the video is laggy". This path costs zero core
     * calls. Track ids are mpv ids, which is what aid/sid expect back.
     */
    private data class MpvTrack(
        val id: Int,
        val title: String?,
        val language: String?,
        val selected: Boolean,
        val forced: Boolean,
    )

    private fun tracks(type: String): List<MpvTrack> {
        val json = mpv.cachedString("track-list") ?: return emptyList()
        val out = ArrayList<MpvTrack>()
        for (obj in topLevelObjects(json)) {
            if (jsonField(obj, "type") != type) continue
            val id = jsonField(obj, "id")?.toIntOrNull() ?: continue
            out += MpvTrack(
                id = id,
                title = jsonField(obj, "title"),
                language = jsonField(obj, "lang"),
                selected = jsonField(obj, "selected") == "true",
                forced = jsonField(obj, "forced") == "true",
            )
        }
        return out
    }

    /** Split a JSON array into its top-level object substrings. */
    private fun topLevelObjects(json: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in json.indices) {
            val c = json[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(json.substring(start, i + 1)); start = -1 } }
            }
        }
        return out
    }

    /**
     * A field's value from a flat JSON object: strings unescaped, other
     * scalars verbatim ("true"/"false"/numbers). Null when absent.
     */
    private fun jsonField(obj: String, key: String): String? {
        val needle = "\"$key\":"
        var i = -1
        // Find the needle OUTSIDE of any string value.
        var inStr = false
        var esc = false
        var j = 0
        while (j < obj.length) {
            val c = obj[j]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                j++
                continue
            }
            if (c == '"') {
                if (obj.startsWith(needle, j)) { i = j + needle.length; break }
                inStr = true
            }
            j++
        }
        if (i < 0) return null
        while (i < obj.length && obj[i] == ' ') i++
        if (i >= obj.length) return null
        return if (obj[i] == '"') {
            val sb = StringBuilder()
            var k = i + 1
            while (k < obj.length) {
                val c = obj[k]
                if (c == '\\' && k + 1 < obj.length) {
                    val n = obj[k + 1]
                    sb.append(
                        when (n) {
                            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                            else -> n
                        },
                    )
                    k += 2
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    k++
                }
            }
            sb.toString()
        } else {
            val end = obj.indexOfFirst(i) { it == ',' || it == '}' }
            obj.substring(i, if (end < 0) obj.length else end).trim()
        }
    }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (k in from until length) if (pred(this[k])) return k
        return -1
    }

    private fun MpvTrack.label(fallback: String): String =
        title ?: language ?: "$fallback $id"

    // Stock's track contract (buildTracksJson): `index` is the 0-based
    // position in the filtered list -- the currency the whole UI layer
    // trades in -- and `id` is the mpv id, which only the controller's
    // position->id resolution ever hands to mpv. Publishing mpv ids as
    // indices sent raw positions into aid: position 0 became aid=0, a track
    // that does not exist, and every session started mute.
    override fun audioTracks(): List<com.nuvio.app.features.player.AudioTrack> =
        tracks("audio").mapIndexed { position, t ->
            com.nuvio.app.features.player.AudioTrack(
                index = position,
                id = t.id.toString(),
                label = t.label("Track"),
                language = t.language,
                isSelected = t.selected,
            )
        }

    override fun subtitleTracks(): List<com.nuvio.app.features.player.SubtitleTrack> =
        tracks("sub").mapIndexed { position, t ->
            com.nuvio.app.features.player.SubtitleTrack(
                index = position,
                id = t.id.toString(),
                label = t.label("Subtitle"),
                language = t.language,
                isSelected = t.selected,
                isForced = t.forced,
            )
        }

    override fun stop() {
        StartupTrace.mark("stop()")
        hasFile = false
        chrome?.priming = false
        mpv.command("stop")
        // Upstream destroys the player's web view here and builds a new one
        // for the next session; reloading is the same thing for page state,
        // and doing it now (nothing is on screen) means the next session
        // finds the page already loaded -- upstream's warmup trick.
        chrome?.reloadPage()
    }

    override fun isOpening(): Boolean = awaitingFirstFrame

    override fun snapshot(): WaylandVideoBridge.Delegate.State {
        if (!hasFile) return WaylandVideoBridge.Delegate.State()
        // Cache reads only: observed properties arrive on the event thread;
        // nothing here touches the mpv core. Polling it -- from any thread --
        // contends with the core while the video thread paces inside
        // render(), which measured as 66ms present gaps at the poll rate.
        val position = mpv.cachedDouble("time-pos") ?: 0.0
        val duration = mpv.cachedDouble("duration") ?: 0.0
        // demuxer-cache-time is an absolute timestamp, not a length, which is
        // exactly what a buffered *position* wants.
        val buffered = mpv.cachedDouble("demuxer-cache-time") ?: position
        // Only what this function still needs: the loading/buffering reads
        // live in isLoadingNow(), which both this and the page's update call.
        val paused = mpv.cachedBoolean("pause") ?: false
        val idle = mpv.cachedBoolean("idle-active") ?: false
        return WaylandVideoBridge.Delegate.State(
            positionMs = (position * 1000).toLong(),
            durationMs = (duration * 1000).toLong(),
            bufferedMs = (buffered * 1000).toLong(),
            isPlaying = !paused && !idle,
            isBuffering = isLoadingNow(),
            hasEnded = mpv.cachedBoolean("eof-reached") ?: false,
            // The speed cycler and its label both read this back; without it
            // they see 1x forever and cycling sticks at the first step.
            playbackSpeed = (mpv.cachedDouble("speed") ?: 1.0).toFloat(),
            volumeLevel = audioLevel().let { if (it.isMuted) 0f else it.fraction },
        )
    }

    companion object {
        /** Everything snapshot()/audioLevel() read, pushed rather than polled. */
        val OBSERVED_PROPERTIES = listOf(
            "time-pos", "duration", "demuxer-cache-time", "pause",
            "idle-active", "seeking", "paused-for-cache", "eof-reached",
            "speed", "volume", "mute", "core-idle",
            // Current track selection. Observed so a re-select of what is
            // already playing can be recognised and skipped -- see
            // selectAudioTrack. They change only when a track does.
            "aid", "sid",
            // As STRING this arrives as the full JSON blob -- pushed by the
            // core only when tracks actually change, never polled.
            "track-list",
        )
    }
}
