package com.nuvio.wayland

import com.nuvio.wayland.wpe.WpeChrome
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * The web chrome as a GPU layer, owned entirely by [UiPipeline]'s thread.
 *
 * This is where the fullscreen lag went. The old path did, per chrome frame and
 * on the presenting thread: memcpy the wl_shm export, copy it AGAIN into a
 * fresh ByteArray, build a Skia raster Image from it, upload, blit. Every one
 * of those steps is O(pixels), so a windowed 870k-pixel chrome was fine and a
 * 3.7M-pixel fullscreen one was not.
 *
 * Here nothing per-frame is O(pixels) on the CPU:
 * - GPU path: the exported EGLImage is bound straight to a texture
 *   (glEGLImageTargetTexture2DOES). No copy at all, at any size.
 * - SHM path: one glTexSubImage2D into a PERSISTENT texture. Still a copy --
 *   the software renderer gives us no choice -- but it is a single upload
 *   rather than three passes plus an allocation, and no Skia object churn.
 *
 * Either way the result is one texture, composited into the UI buffer with a
 * single quad, so the presenting thread's cost does not change at all.
 *
 * Threading: every method here must run on the UI thread with its GL context
 * current. [WpeChrome] hands frames over through its own atomics, so the GLib
 * thread never touches GL.
 */
class ChromeLayer(private val chrome: WpeChrome) {

    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofShared()
    private val egl = SymbolLookup.libraryLookup("libEGL.so.1", arena)

    /**
     * glEGLImageTargetTexture2DOES is an extension entry point; per the EGL
     * spec it must come from eglGetProcAddress, not dlsym.
     */
    private val imageTargetTexture by lazy {
        val getProc = linker.downcallHandle(
            egl.find("eglGetProcAddress").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS),
        )
        val addr = Arena.ofConfined().use { a ->
            getProc.invokeExact(a.allocateFrom("glEGLImageTargetTexture2DOES")) as MemorySegment
        }
        check(!addr.equals(MemorySegment.NULL)) { "glEGLImageTargetTexture2DOES unavailable" }
        linker.downcallHandle(addr, FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS))
    }

    private var program = 0
    private var vao = 0
    private var uFlipY = -1
    private var uTex = -1

    /** The sampled texture: EGLImage target on the GPU path, upload target on SHM. */
    private var texture = 0
    private var texW = 0
    private var texH = 0

    /**
     * What currently backs [texture]. This matters because BOTH export slots
     * are filled: a GPU-mode run that produced a SHM buffer would otherwise
     * glTexSubImage2D into storage owned by an EGLImage, whose dimensions
     * happen to match but whose memory is WPE's, not ours. Storage is
     * reallocated on any change of kind.
     */
    private enum class Backing { NONE, EGL_IMAGE, UPLOAD }
    private var backing = Backing.NONE

    /** Held so WPE cannot reuse the buffer while it is still being sampled. */
    private var displayedImage: MemorySegment? = null

    /** False until a frame has actually landed; nothing is drawn before that. */
    @Volatile private var haveContent = false

    // ---- telemetry (read by report()) ----
    @Volatile private var updates = 0L
    @Volatile private var updateNanos = 0L
    @Volatile private var maxUpdateNanos = 0L
    @Volatile private var drawNanos = 0L
    @Volatile private var importErrors = 0L
    @Volatile private var uploadedBytes = 0L
    private var loggedImportOk = false

    /**
     * The page's frame budget, not our cost: WPE renders the next frame only
     * when acked, so this is what paces it. 30fps matches the SHM path's long
     * standing throttle; the GPU path can afford more, hence the lever.
     */
    private val ackDelayMs: Int by lazy {
        // The 30fps cap dates from the SHM path, where a frame cost 4-12ms of
        // CPU raster plus ~220MB/s of copying. On the GPU path a frame costs
        // ~0.1ms and copies nothing, so the cap is pure input latency: the
        // page was measured running at 15-21fps while visible, which is what
        // makes hover and scrubbing feel laggy. Let it run at display rate
        // there; keep the old cap for SHM.
        val fps = System.getProperty("nuvio.wayland.chromeFps")?.toIntOrNull()
            ?: if (chrome.gpuActive) 120 else 30
        (1000 / fps).coerceAtLeast(1)
    }

    /**
     * Whether the sampled texture has to be flipped vertically when drawn.
     * Tracked PER SOURCE, deliberately: the two paths are separate questions
     * and a single shared constant is what flipped the chrome upside down
     * twice during development.
     *
     * Both answers are "no", and here is why, so it cannot drift again:
     *
     * The destination is [UiPipeline]'s buffer, wrapped by Skia with
     * SurfaceOrigin.TOP_LEFT. TOP_LEFT means texel row 0 IS the top of the
     * image -- the same convention the video path documents ("mpv renders with
     * flip_y=0 ... leaving the image top-row-first"). So in the quad below,
     * NDC y=-1 is the canvas TOP, and sampling v=0 there draws the source's
     * row 0 at the top.
     *
     * - SHM: wl_shm row 0 is the top of the page, uploaded straight through by
     *   glTexSubImage2D, so texel row 0 is the page top. No flip.
     * - GPU: WPE's exported EGLImage is a wayland buffer and follows the same
     *   top-down convention, NOT GL's bottom-left one. No flip. (Assuming it
     *   was GL-oriented is what produced the first upside-down build.)
     *
     * Whatever the answer, the correction is a texture coordinate and never a
     * row-flipping copy: keeping the CPU out of the per-frame path is the
     * whole point. Input is unaffected either way -- pointer coordinates go to
     * WPE in page space, top-down, and nothing here touches that.
     */
    private val flipShm = System.getProperty("nuvio.wayland.chromeFlipShm")?.toBoolean() ?: false
    private val flipGpu = System.getProperty("nuvio.wayland.chromeFlipGpu")?.toBoolean() ?: false

    private val probe = System.getProperty("nuvio.wayland.chromeProbe")?.toBoolean() ?: false
    private var probesLeft = if (probe) 3 else 0

    // ---- GL setup ----

    private fun ensureProgram() {
        if (program != 0) return
        // Core profile: a VAO must be bound for any draw, and the quad comes
        // from gl_VertexID so there is no vertex buffer to manage at all.
        val vs = """
            #version 330 core
            out vec2 vUv;
            uniform bool uFlipY;
            void main() {
                vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                vUv = vec2(p.x, uFlipY ? 1.0 - p.y : p.y);
            }
        """.trimIndent()
        val fs = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            void main() { fragColor = texture(uTex, vUv); }
        """.trimIndent()
        program = GL20.glCreateProgram()
        for ((type, src) in listOf(GL20.GL_VERTEX_SHADER to vs, GL20.GL_FRAGMENT_SHADER to fs)) {
            val sh = GL20.glCreateShader(type)
            GL20.glShaderSource(sh, src)
            GL20.glCompileShader(sh)
            if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                error("chrome shader failed: ${GL20.glGetShaderInfoLog(sh)}")
            }
            GL20.glAttachShader(program, sh)
            GL20.glDeleteShader(sh)
        }
        GL20.glLinkProgram(program)
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("chrome program link failed: ${GL20.glGetProgramInfoLog(program)}")
        }
        uFlipY = GL20.glGetUniformLocation(program, "uFlipY")
        uTex = GL20.glGetUniformLocation(program, "uTex")
        vao = GL30.glGenVertexArrays()
    }

    private fun ensureTexture(): Int {
        if (texture == 0) {
            texture = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        }
        return texture
    }

    // ---- per-frame ----

    /**
     * Adopt the newest exported chrome frame, if there is one. Returns true
     * when the texture changed, which is what tells [UiPipeline] it has
     * something new to publish even though Compose itself is idle.
     */
    fun update(): Boolean {
        // A clear IS a change: the chrome is composited into the UI texture,
        // so dropping it has to produce one more frame without it. Reporting
        // "nothing changed" here made UiPipeline skip the re-render and left
        // the chrome on screen after playback ended.
        //
        // It must not SHORT-CIRCUIT the update, though: returning early here
        // skipped adopting the waiting frame and, with it, the ack that is
        // the page's permission to render the next one -- after the first
        // hide the chrome could never come back.
        val dirty = dirtySincePublish
        dirtySincePublish = false
        if (!chrome.visible && !chrome.priming) {
            // Hidden: never adopt. WpeChrome drops exports while hidden, but
            // one queued microseconds before the flag flipped is still
            // waiting here -- adopting it puts the chrome straight back on
            // screen after the clear, which is exactly why leaving a stream
            // sometimes left the chrome up and sometimes did not. Drain it.
            //
            // Draining is not enough: that frame was exported while still
            // visible, so WpeChrome took its visible branch and scheduled no
            // ack for it. WPE renders the next frame only once acked, so
            // dropping this one silently froze the page FOREVER -- its frame
            // counter stopped, the reveal gate (framesExported past a mark)
            // could never be met again, and every stream after the first had
            // no chrome at all. Ack it on the same slow trickle WpeChrome
            // uses while hidden: the page keeps breathing at ~5fps, which
            // costs nothing and keeps it revealable.
            var drained = chrome.takeEglImage()?.let { chrome.releaseImageAsync(it); true } ?: false
            if (chrome.takeShmFrame() != null) drained = true
            if (drained) chrome.ackFrameAfter(HIDDEN_ACK_MS)
            return dirty
        }
        val start = System.nanoTime()
        val changed = if (chrome.gpuActive) updateFromEglImage() else updateFromShm()
        if (changed) {
            val ns = System.nanoTime() - start
            updates++
            updateNanos += ns
            if (ns > maxUpdateNanos) maxUpdateNanos = ns
            // The ack is the page's permission to render the next frame, and
            // it is only ever valid once per export -- see WpeChrome.
            //
            // Unpaced while priming: those frames are the ones a reveal is
            // waiting on, and pacing them is time the user spends looking at
            // black. The window is a handful of frames long, so the storm the
            // pacing exists to prevent cannot get going.
            if (composited) chrome.ackFrameAfter(ackDelayMs) else chrome.ackFrame()
            StartupTrace.mark(
                "layer took chrome frame taken=${chrome.framesTaken} " +
                    "${texW}x$texH composited=$composited",
            )
            if (probesLeft > 0) {
                probesLeft--
                probeOrientation()
            }
        }
        // A frame adopted while priming changes the texture but not the
        // screen, so it is not a reason to publish a UI frame.
        return (changed && composited) || dirty
    }

    /**
     * Whether the adopted frame is drawn at all.
     *
     * Separate from having content on purpose: through the session-start
     * window the layer takes the page's frames without showing them, so that
     * when the reveal comes the texture already holds this session's opening
     * overlay and the reveal is a draw rather than a wait. That wait was the
     * black flash between clicking a stream and the loading page.
     */
    @Volatile private var composited = false

    /** For the startup trace: read from the presenting and UI threads. */
    val isComposited: Boolean get() = composited
    val hasContent: Boolean get() = haveContent

    /**
     * Start or stop drawing what the layer already holds.
     *
     * Either direction changes the composite without a new frame arriving, so
     * both have to publish one: [UiPipeline] re-renders only when something
     * says it must, and "the chrome appeared" and "the chrome went away" are
     * both that something.
     */
    fun setComposited(on: Boolean) {
        if (on == composited) return
        composited = on
        if (haveContent) dirtySincePublish = true
    }

    private fun updateFromEglImage(): Boolean {
        val image = chrome.takeEglImage() ?: return false
        ensureProgram()
        val tex = ensureTexture()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        // Clear any stale error so the check below is about this call only.
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
        imageTargetTexture.invokeExact(GL11.GL_TEXTURE_2D, chrome.eglImageOf(image))
        val err = GL11.glGetError()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)

        if (err != GL11.GL_NO_ERROR) {
            // The import is the one thing that could not be fully guaranteed
            // ahead of time: the image belongs to fdo's isolated EGLDisplay,
            // not this context's. Say so plainly instead of drawing nothing
            // and leaving the cause to guesswork.
            if (importErrors++ == 0L) {
                System.err.println(
                    "[chrome] glEGLImageTargetTexture2DOES failed (glError=0x${err.toString(16)}): " +
                        "this driver will not import fdo's EGLImage into the UI context. " +
                        "Re-run with -Pnuvio.wayland.chromeGpu=false for the SHM path.",
                )
            }
            chrome.releaseImageAsync(image)
            // Still ack: an export that goes unanswered stops the page
            // rendering for good, which would turn a recoverable import
            // failure into a permanently dead chrome.
            chrome.ackFrameAfter(ackDelayMs)
            return false
        }

        texW = chrome.imageWidth(image)
        texH = chrome.imageHeight(image)
        backing = Backing.EGL_IMAGE
        if (!loggedImportOk) {
            loggedImportOk = true
            println("[chrome] EGLImage import OK across the isolated display -- ${texW}x$texH, zero-copy")
        }
        // Only now is the predecessor safe to hand back: its successor is
        // bound, so nothing samples it any more.
        displayedImage?.let { chrome.releaseImageAsync(it) }
        displayedImage = image
        haveContent = true
        return true
    }

    private fun updateFromShm(): Boolean {
        val frame = chrome.takeShmFrame() ?: return false
        ensureProgram()
        val tex = ensureTexture()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        // Reallocate when the size changes OR when the texture is still backed
        // by an EGLImage: sub-uploading into WPE's memory would be a write into
        // a buffer we do not own. Steady state is neither, so this is a plain
        // sub-image upload into storage that already exists.
        if (frame.width != texW || frame.height != texH || backing != Backing.UPLOAD) {
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frame.width, frame.height, 0,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?,
            )
            texW = frame.width
            texH = frame.height
            backing = Backing.UPLOAD
        }
        // Length is the frame's own w*h*4, tightly packed: WpeChrome allocates
        // and fills it from the wl_shm buffer's real dimensions and stride, so
        // there is no cached or assumed size anywhere in this call.
        val expected = frame.width.toLong() * frame.height * 4
        require(frame.pixels.remaining().toLong() == expected) {
            "chrome SHM frame ${frame.width}x${frame.height} needs $expected bytes, " +
                "got ${frame.pixels.remaining()}"
        }
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
            GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, frame.pixels,
        )
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        uploadedBytes += frame.width.toLong() * frame.height * 4
        haveContent = true
        return true
    }

    /**
     * Composite the chrome over whatever is already in the bound framebuffer.
     *
     * Drawn at native size anchored to the canvas top-left, never stretched:
     * during a resize the exported frame briefly disagrees with the window
     * (they run a frame or two apart), and scaling it would make the chrome
     * visibly rubber-band. Because the UI buffer is a Skia TOP_LEFT surface,
     * texel row 0 is the canvas top, so top-aligned means GL rows 0..texH-1 --
     * the viewport sits at the origin, with no offset.
     */
    /**
     * [originBottomLeft] is the destination's convention, not the source's.
     * A Skia TOP_LEFT surface puts row 0 at the top; the window's default
     * framebuffer puts it at the bottom, so the same quad drawn into each
     * comes out the other way up.
     */
    fun draw(targetWidth: Int, targetHeight: Int, originBottomLeft: Boolean = false) {
        if (!composited || !haveContent || texture == 0 || texW <= 0 || texH <= 0) {
            if (drawsLogged < 3 &&
                System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true
            ) {
                drawsLogged++
                println("[chrome-layer] draw skipped (haveContent=$haveContent tex=$texture)")
            }
            return
        }
        drawsLogged = 0
        val t = System.nanoTime()
        ensureProgram()

        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glColorMask(true, true, true, true)
        GL11.glEnable(GL11.GL_BLEND)
        // Premultiplied alpha on both sides: WebKit's output is premultiplied
        // and so is Compose's, so this is a straight source-over.
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)

        GL11.glViewport(0, 0, texW, texH)
        GL20.glUseProgram(program)
        val sourceFlip = if (chrome.gpuActive) flipGpu else flipShm
        GL20.glUniform1i(uFlipY, if (sourceFlip != originBottomLeft) 1 else 0)
        GL20.glUniform1i(uTex, 0)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL30.glBindVertexArray(vao)
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
        GL30.glBindVertexArray(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL20.glUseProgram(0)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glViewport(0, 0, targetWidth, targetHeight)

        drawNanos += System.nanoTime() - t
    }

    /**
     * Coarse band profile of the chrome texture, for debugging only (three
     * frames, behind -Pnuvio.wayland.chromeProbe). Never on a render path --
     * the GPU path reads nothing back.
     *
     * A WARNING, learned the hard way: the ALPHA it reports is not
     * trustworthy on this driver. Asked to settle the GPU path's orientation
     * it answered "100% coverage" for every band of a chrome that is mostly
     * transparent -- the same NVIDIA GPU->CPU alpha corruption that pushed
     * this project onto the software path in the first place. Blending in the
     * actual draw is fine; only the readback is wrong. So this can show where
     * geometry sits, and cannot be used to reason about transparency.
     */
    private fun probeOrientation() {
        if (texture == 0 || texW <= 0 || texH <= 0) return
        val fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0,
        )
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
            val bands = 8
            val buf = java.nio.ByteBuffer.allocateDirect(texW * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            val profile = StringBuilder()
            for (b in 0 until bands) {
                // Sample one row per band; glReadPixels row 0 is GL row 0.
                val row = (b * texH / bands).coerceIn(0, texH - 1)
                buf.clear()
                GL11.glReadPixels(
                    0, row, texW, 1, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf,
                )
                var cover = 0L
                for (x in 0 until texW) {
                    val a = buf.get(x * 4 + 3).toInt() and 0xFF
                    if (a > 8) cover++
                }
                profile.append("%d%%".format(cover * 100 / texW))
                if (b < bands - 1) profile.append(' ')
            }
            println(
                "[chrome-probe] path=${if (chrome.gpuActive) "gpu" else "shm"} ${texW}x$texH " +
                    "glRow0..glRowMax alphaCoverage: $profile",
            )
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        GL30.glDeleteFramebuffers(fbo)
    }

    /**
     * Stop drawing and give any held buffer back. Called when the activity
     * gate hides the chrome, so a hidden chrome costs nothing at all -- no
     * quad, and no WPE buffer pinned by us.
     */
    fun clear() {
        if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
            println("[chrome-layer] clear(haveContent=$haveContent)")
        }
        // Only worth a repaint if something was actually on screen.
        setComposited(false)
        haveContent = false
        displayedImage?.let { chrome.releaseImageAsync(it) }
        displayedImage = null
    }

    /**
     * Set when the composite changed without a frame doing it -- revealed,
     * hidden, cleared. Consumed by [update], which is where [UiPipeline] asks
     * whether there is anything new to publish.
     */
    private var dirtySincePublish = false
    private var drawsLogged = 0

    private companion object {
        /** Hidden-page ack pacing, matching WpeChrome's own hidden trickle. */
        const val HIDDEN_ACK_MS = 200
    }

    /** Per-second telemetry. This is where chrome cost is paid and reported. */
    fun report(elapsedSeconds: Double): String {
        val u = updates; updates = 0
        val ns = updateNanos; updateNanos = 0
        val mx = maxUpdateNanos; maxUpdateNanos = 0
        val dn = drawNanos; drawNanos = 0
        val bytes = uploadedBytes; uploadedBytes = 0
        val mpx = texW.toDouble() * texH / 1e6
        val avgMs = if (u > 0) ns / 1e6 / u else 0.0
        val drawMs = if (u > 0) dn / 1e6 / u else 0.0
        return (
            "chrome: path=%s frames/s=%.1f adopt=%.2fms adoptMax=%.2fms blit=%.2fms " +
                "size=%dx%d (%.2fMpx) perMpx=%.2fms copied=%.1fMB/s errs=%d"
            ).format(
            if (chrome.gpuActive) "gpu" else "shm",
            u / elapsedSeconds, avgMs, mx / 1e6, drawMs,
            texW, texH, mpx,
            if (mpx > 0) avgMs / mpx else 0.0,
            bytes / 1e6 / elapsedSeconds,
            importErrors,
        )
    }

    fun close() {
        displayedImage?.let { chrome.releaseImageAsync(it) }
        displayedImage = null
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0 }
        backing = Backing.NONE
        texW = 0; texH = 0
        if (vao != 0) { GL30.glDeleteVertexArrays(vao); vao = 0 }
        if (program != 0) { GL20.glDeleteProgram(program); program = 0 }
        haveContent = false
    }
}
