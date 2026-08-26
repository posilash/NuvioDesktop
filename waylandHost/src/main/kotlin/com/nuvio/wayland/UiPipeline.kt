package com.nuvio.wayland

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext

/**
 * The UI half of the host: a dedicated thread that owns a third, shared GL
 * context, its own Skia [DirectContext], and the whole Compose scene.
 *
 * This is [VideoPipeline]'s shape applied to Compose. The measured problem it
 * exists to solve: `ComposeScene.render()` is not just a rasterization -- it
 * applies snapshot changes, recomposes, runs effects and then draws, and at
 * fullscreen that whole frame costs 2-70ms. Running it on the thread that also
 * composites and swaps means every scene frame delays a video present, which
 * the eye reads as judder (the vsync-gap histogram spreads from a clean 6v/7v
 * pattern to 1v-11v). Freezing the scene fixed the video and froze the app,
 * so the coupling had to be broken rather than tuned.
 *
 * Here the scene lives entirely on this thread and publishes finished textures.
 * The presenting thread only ever draws the newest one, at constant cost.
 *
 * Buffers are triple: one being rendered into, one published, one possibly
 * still being read by the presenting thread. Textures are shared between the
 * contexts (same share group); framebuffers are not shareable, so each side
 * wraps the texture in its own. A GL fence accompanies every publish -- the
 * consumer must wait on it before sampling, since these renders happen on
 * another context whose commands are not otherwise ordered with the consumer's.
 *
 * Thread ownership is absolute: construction, setContent, size/density changes,
 * input, render and close all happen on [thread]. Nothing else may touch the
 * scene.
 */
class UiPipeline(
    private val uiWindow: Long,
    bufferCount: Int = 3,
) {
    class Buffer {
        var texture = 0
        var fbo = 0
        var renderTarget: BackendRenderTarget? = null
        var surface: Surface? = null
        var width = 0
        var height = 0
        /** Identity for consumer-side wrapper caches; bumped on reallocation. */
        var generation = 0
    }

    private val lock = Object()
    private val buffers = Array(bufferCount) { Buffer() }
    private var front: Buffer? = null      // latest published, not yet taken
    private var displayed: Buffer? = null  // held by the consumer
    private var frontFence = 0L

    /** Skia context for the scene. Created on, and only usable from, [thread]. */
    lateinit var context: DirectContext
        private set

    /** The Compose scene. Only touch it from [thread] (see [post]/[invokeAndWait]). */
    lateinit var scene: ComposeScene

    /**
     * Owns the frame clock in 1.12: recomposition, effects and animations run
     * from performFrame(), and the scene only draws. Lives here because it must
     * share the thread and dispatcher the scene is confined to.
     */
    lateinit var frameRecomposer: androidx.compose.ui.platform.FrameRecomposer
        private set

    // Requested framebuffer size and density, written by the presenting thread.
    @Volatile private var targetWidth = 0
    @Volatile private var targetHeight = 0
    @Volatile private var targetDensity = 1f
    @Volatile private var sizeDirty = false

    @Volatile private var running = true
    /** Set by Compose's invalidate callback, by resizes, and by posted work. */
    @Volatile private var framePending = true
    private var thread: Thread? = null
    private val ready = CountDownLatch(1)
    @Volatile private var initError: Throwable? = null

    /** Work queued for the UI thread: the Compose dispatcher and input both use it. */
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    /** Called (from this thread) after each publish; wired to wake the host loop. */
    @Volatile var onFrame: (() -> Unit)? = null


    /**
     * Set to render the scene into Vulkan buffers instead of GL ones.
     *
     * Everything else about this class is unchanged, and deliberately so: the
     * scene costs 16-28ms a frame on either backend (measured on both), and
     * what makes that survivable is that it is paid HERE and published, never
     * waited on by the thread that presents. Drawing it inside the host's frame
     * instead dropped that loop from 165fps to 22-33.
     */
    @Volatile var vk: VkPresenter? = null
    private var vkRecorder: org.jetbrains.skia.gpu.graphite.Recorder? = null
    private val vkBuffers = arrayOfNulls<VkPresenter.UiBuffer>(3)
    private var vkVersion = 0L
    private var vkFront: VkPresenter.UiBuffer? = null
    private var vkDisplayed: VkPresenter.UiBuffer? = null

    /** Newest published scene image, for the presenting thread. Null before the first. */
    fun acquireVkFrame(): VkPresenter.UiBuffer? {
        synchronized(lock) {
            val f = vkFront
            if (f != null) { vkFront = null; vkDisplayed = f }
            return vkDisplayed
        }
    }

    // The chrome used to be composited into this thread's buffer. It is drawn
    // by the presenting thread now: a Compose frame has no upper bound, and
    // the controls must not wait on one. See where ChromeLayer is created.

    // Telemetry, read by report(). This is where scene cost is now reported --
    // it no longer exists anywhere on the present path.
    @Volatile private var renders = 0L
    @Volatile private var renderNanos = 0L
    // "The scene is slow" is two problems: Compose recomposing and drawing,
    // versus this thread blocking in the driver behind everything else on the
    // GPU. One number cannot tell them apart.
    @Volatile private var composeNanos = 0L
    @Volatile private var flushNanos = 0L
    @Volatile private var maxRenderNanos = 0L
    @Volatile private var lastGeneration = 0
    @Volatile private var renderErrors = 0L

    /** Cap on scene frames per second; animations need a steady clock, not a spin. */
    private val frameIntervalNs: Long =
        1_000_000_000L / (System.getProperty("nuvio.wayland.uiFps")?.toIntOrNull() ?: 60)

    // ---- producer API (any thread) ----

    /**
     * Compose's invalidate hook, and the general "there is something to draw"
     * signal. Cheap and idempotent.
     */
    fun requestFrame() {
        framePending = true
        thread?.let { LockSupport.unpark(it) }
    }

    /** Queue work for the UI thread. */
    fun post(task: Runnable) {
        tasks.add(task)
        thread?.let { LockSupport.unpark(it) }
    }

    /** Run [block] on the UI thread and wait. Never call this FROM the UI thread. */
    fun <T> invokeAndWait(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Coroutine dispatcher for the scene: everything Compose launches lands here. */
    val dispatcher: CoroutineDispatcher = UiThreadDispatcher { post(it) }

    /**
     * New framebuffer size / display scale, in framebuffer pixels. Called from
     * the presenting thread; the UI thread applies it to the scene and
     * reallocates its buffers.
     */
    fun resize(width: Int, height: Int, density: Float) {
        if (width == targetWidth && height == targetHeight && density == targetDensity) return
        targetWidth = width
        targetHeight = height
        targetDensity = density
        sizeDirty = true
        requestFrame()
    }

    /**
     * Start the thread and build the scene on it. [sceneFactory] runs on the UI
     * thread with the GL context current and [context] initialised.
     */
    fun start(
        width: Int,
        height: Int,
        density: Float,
        sceneFactory: (androidx.compose.ui.platform.FrameRecomposer) -> ComposeScene,
    ) {
        targetWidth = width
        targetHeight = height
        targetDensity = density
        thread = Thread({ run(sceneFactory) }, "nuvio-ui").apply {
            isDaemon = true
            start()
        }
    }

    /** Blocks until the scene exists (or its construction failed). */
    fun awaitReady() {
        ready.await()
        initError?.let { throw IllegalStateException("UI pipeline failed to start", it) }
    }

    fun stop() {
        running = false
        thread?.let { LockSupport.unpark(it) }
        thread?.join(15_000)
    }

    /** Per-second telemetry line. Scene cost lives here now, not in the present path. */
    fun report(elapsedSeconds: Double): String {
        val r = renders; renders = 0
        val ns = renderNanos; renderNanos = 0
        val cns = composeNanos; composeNanos = 0
        val fns = flushNanos; flushNanos = 0
        val mx = maxRenderNanos; maxRenderNanos = 0
        val avgMs = if (r > 0) ns / 1e6 / r else 0.0
        return (
            "ui: scenes/s=%.0f sceneAvg=%.1fms compose=%.1fms flush=%.1fms " +
                "sceneMax=%.1fms size=%dx%d gen=%d errs=%d"
            ).format(
            r / elapsedSeconds, avgMs,
            if (r > 0) cns / 1e6 / r else 0.0,
            if (r > 0) fns / 1e6 / r else 0.0,
            mx / 1e6, targetWidth, targetHeight, lastGeneration, renderErrors,
        )
    }

    // ---- the UI thread ----

    private fun run(sceneFactory: (androidx.compose.ui.platform.FrameRecomposer) -> ComposeScene) {
        try {
            // Claim "main dispatcher thread" BEFORE anything composes: the
            // lifecycle registries Compose builds during the apply phase assert
            // against Dispatchers.Main.immediate, and they are constructed the
            // first time the scene renders. See NuvioMainDispatcher.
            NuvioMainDispatcher.uiThread = Thread.currentThread()
            NuvioMainDispatcher.post = { post(it) }

            // The context is this thread's for its whole life. The window is
            // hidden and never swapped, so nothing here blocks on vsync.
            glfwMakeContextCurrent(uiWindow)
            GL.createCapabilities()
            context = DirectContext.makeGL()
            frameRecomposer = androidx.compose.ui.platform.FrameRecomposer(dispatcher) { requestFrame() }
            scene = sceneFactory(frameRecomposer)
        } catch (t: Throwable) {
            initError = t
            running = false
            ready.countDown()
            return
        }
        ready.countDown()

        try {
            loop()
        } catch (t: Throwable) {
            System.err.println("[wayland-ui] UI thread died")
            t.printStackTrace()
        } finally {
            // Everything Skia, Compose and GL owns here must die on this
            // thread, with this context current.
            runCatching { scene.close() }.onFailure { it.printStackTrace() }
            synchronized(lock) {
                if (frontFence != 0L) { GL32.glDeleteSync(frontFence); frontFence = 0L }
            }
            for (b in buffers) releaseBuffer(b)
            // The Vulkan scene buffers belong to this thread too, and they hold
            // device memory the presenter is about to be destroyed with.
            vk?.let { p ->
                for (i in vkBuffers.indices) {
                    vkBuffers[i]?.let { p.destroyUiBuffer(it) }
                    vkBuffers[i] = null
                }
            }
            vkFront = null
            vkDisplayed = null
            // This thread's own Recorder. Left open, it outlives the Context
            // the host closes next, and the pair faults in _nInvokeFinalizer
            // on the way out. Everything Graphite here dies on this thread,
            // same rule as the GL objects above.
            runCatching { vkRecorder?.close() }.onFailure { it.printStackTrace() }
            vkRecorder = null
            runCatching { context.close() }.onFailure { it.printStackTrace() }
        }
    }

    private var lastRenderNs = 0L

    private fun loop() {
        while (running) {
            drainTasks()
            if (!running) return

            if (!framePending) {
                // Parked until an invalidation, a chrome frame, a resize or
                // posted work. The timeout is only a shutdown/robustness
                // backstop.
                LockSupport.parkNanos(100_000_000L)
                continue
            }

            // Steady cadence rather than a spin: animations keep invalidating,
            // and without this the thread would rasterize as fast as the GPU
            // allows and burn a core for frames nobody presents.
            val now = System.nanoTime()
            val due = lastRenderNs + frameIntervalNs
            if (lastRenderNs != 0L && now < due) {
                LockSupport.parkNanos(due - now)
                continue
            }

            // Cleared BEFORE rendering: invalidations raised during the render
            // (Compose's frame clock resumes animations inside render()) must
            // re-arm it rather than be swallowed.
            val sceneWanted = framePending
            framePending = false

            // Adopt a waiting chrome frame. This is GL work against the
            // exported EGLImage (or a texture upload on the SHM path), so it
            // has to happen here, on the thread that owns the context -- never
            // on the presenting thread, which is the whole point.
            // Woken for nothing that changed a pixel.
            if (!sceneWanted && !sizeDirty) continue

            applyPendingSize()

            val w = targetWidth
            val h = targetHeight
            if (w <= 0 || h <= 0) continue

            val vkp = vk
            if (vkp != null) {
                renderVulkanScene(vkp, w, h)
                continue
            }

            val buf = acquireBuffer(w, h) ?: continue
            val surface = buf.surface ?: continue

            lastRenderNs = System.nanoTime()
            val t = lastRenderNs
            // Transparent: the video hole the player surface punches has to
            // show the layer below, and everything the scene draws stacks above.
            surface.canvas.clear(0x00000000)
            // A throw from render() used to kill this thread outright, which
            // presents as "the app drew one frame and froze" -- the most
            // expensive failure mode there is, because nothing says why. A
            // scene-side fault now degrades the UI and leaves the host alive.
            try {
                frameRecomposer.performFrame(System.nanoTime())
                scene.draw(surface.canvas.asComposeCanvas())
            } catch (t: Throwable) {
                if (renderErrors++ == 0L) {
                    System.err.println("[wayland-ui] scene.render failed (first occurrence)")
                    t.printStackTrace()
                }
            }
            val composeNs = System.nanoTime() - t
            // Submit Skia's recorded work before fencing it.
            context.flush()

            val elapsed = System.nanoTime() - t
            renders++
            renderNanos += elapsed
            composeNanos += composeNs
            flushNanos += elapsed - composeNs
            if (elapsed > maxRenderNanos) maxRenderNanos = elapsed

            // The chrome goes on top, into the same buffer, as one textured
            // quad. Raw GL is deliberate: Skia treats a wrapped render target
            val fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            // The fence must reach the GPU before another context waits on it.
            GL11.glFlush()

            synchronized(lock) {
                if (frontFence != 0L) GL32.glDeleteSync(frontFence)
                front = buf
                frontFence = fence
            }
            lastGeneration = buf.generation
            StartupTrace.publish(false, buf.generation)
            onFrame?.invoke()

            // Animations and pending effects keep the scene dirty; ask for the
            // next frame so the pacing above turns it into a steady cadence.
            if (frameRecomposer.hasPendingWork()) framePending = true
        }
    }

    /** Apply a size/density the host asked for. Only ever on this thread. */
    private fun applyPendingSize() {
        if (!sizeDirty) return
        sizeDirty = false
        val w = targetWidth
        val h = targetHeight
        val d = targetDensity
        if (w > 0 && h > 0) {
            scene.size = IntSize(w, h)
            if (scene.density.density != d) scene.density = Density(d)
        }
    }

    /** The Vulkan half of the render step. Same shape as the GL one below it. */
    private fun renderVulkanScene(vkp: VkPresenter, w: Int, h: Int) {
        val rec = vkRecorder ?: vkp.makeUiRecorder()?.also { vkRecorder = it } ?: return
        val target = pickVkBuffer(vkp, rec, w, h) ?: return

        lastRenderNs = System.nanoTime()
        val t = lastRenderNs
        // Transparent: the video shows through wherever the scene drew nothing.
        target.surface.canvas.clear(0x00000000)
        try {
            frameRecomposer.performFrame(System.nanoTime())
            scene.draw(target.surface.canvas.asComposeCanvas())
        } catch (err: Throwable) {
            if (renderErrors++ == 0L) {
                System.err.println("[wayland-ui] scene.render failed (first occurrence)")
                err.printStackTrace()
            }
        }
        val composeNs = System.nanoTime() - t
        // Async on purpose: blocking here would stall the queue the video runs
        // on, and nothing downstream needs this finished before the next frame.
        // syncCpu here, on THIS thread: the copy below must see finished
        // pixels, and blocking the scene thread costs the host nothing.
        vkp.submitRecorder(rec, syncCpu = true)
        vkp.copyToUiLayer(target.image, target.width, target.height)

        val elapsed = System.nanoTime() - t
        renders++
        renderNanos += elapsed
        composeNanos += composeNs
        flushNanos += elapsed - composeNs
        if (elapsed > maxRenderNanos) maxRenderNanos = elapsed

        target.version = ++vkVersion
        synchronized(lock) { vkFront = target }
        lastGeneration = target.generation
        onFrame?.invoke()
        if (frameRecomposer.hasPendingWork()) framePending = true
    }

    /** A buffer that is neither published nor displayed, at the right size. */
    private fun pickVkBuffer(
        vkp: VkPresenter,
        rec: org.jetbrains.skia.gpu.graphite.Recorder,
        w: Int,
        h: Int,
    ): VkPresenter.UiBuffer? = synchronized(lock) {
        for (b in vkBuffers) {
            if (b != null && b !== vkFront && b !== vkDisplayed &&
                b.width == w && b.height == h
            ) {
                return b
            }
        }
        // None free at this size: take a free slot, reallocating if it held a
        // stale one. Only happens on the first frames and on a resize.
        val idx = vkBuffers.indexOfFirst { b ->
            b == null || (b !== vkFront && b !== vkDisplayed)
        }
        if (idx < 0) return null
        vkBuffers[idx]?.let { vkp.destroyUiBuffer(it) }
        vkBuffers[idx] = null
        val made = vkp.createUiBuffer(rec, w, h) ?: return null
        vkBuffers[idx] = made
        return made
    }

    private fun drainTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            try {
                task.run()
            } catch (t: Throwable) {
                System.err.println("[wayland-ui] task failed")
                t.printStackTrace()
            }
        }
    }

    private var generationCounter = 0

    /** Pick the buffer that is neither published nor being displayed. */
    private fun acquireBuffer(w: Int, h: Int): Buffer? {
        val buf = synchronized(lock) {
            buffers.firstOrNull { it !== front && it !== displayed }
        } ?: return null
        if (buf.texture == 0 || buf.width != w || buf.height != h) {
            releaseBuffer(buf)
            buf.texture = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, buf.texture)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?,
            )
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            buf.fbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, buf.fbo)
            GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, buf.texture, 0,
            )
            // Zero it once so a buffer that is presented before its first scene
            // render shows nothing rather than uninitialised memory.
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

            val rt = BackendRenderTarget.makeGL(
                w, h, 0, 8, buf.fbo, FramebufferFormat.GR_GL_RGBA8,
            )
            // TOP_LEFT on both sides of the handoff: the presenting thread wraps
            // the same texture the same way, so the round trip is the identity.
            val surface = Surface.makeFromBackendRenderTarget(
                context, rt, SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
            ) ?: error("could not wrap UI texture for Skia")
            buf.renderTarget = rt
            buf.surface = surface
            buf.width = w
            buf.height = h
            buf.generation = ++generationCounter
            // The GL objects above were made behind Skia's back; its cached
            // bindings are stale until told. Once per resize, not per frame.
            context.resetGLAll()
            if (LOG) {
                println(
                    "[wayland-ui] alloc gen=${buf.generation} ${w}x$h tex=${buf.texture} " +
                        "alphaType=${surface.imageInfo.colorInfo.alphaType}",
                )
            }
        }
        return buf
    }

    private fun releaseBuffer(b: Buffer) {
        b.surface?.close(); b.surface = null
        b.renderTarget?.close(); b.renderTarget = null
        if (b.fbo != 0) { GL30.glDeleteFramebuffers(b.fbo); b.fbo = 0 }
        if (b.texture != 0) { GL11.glDeleteTextures(b.texture); b.texture = 0 }
        b.width = 0; b.height = 0
    }

    // ---- consumer API (presenting thread, window GL context) ----

    /**
     * Newest published UI layer, for the presenting thread. Transfers the front
     * buffer to `displayed` (freeing the previous one for rendering) and does
     * the cross-context fence wait server-side. [DisplayPipeline.Frame.fresh]
     * is true when this call picked up a new publish; the consumer must then
     * invalidate its Skia caches. Null before the first publish.
     */
    fun acquireFrame(): DisplayPipeline.Frame? {
        var fence = 0L
        var fresh = false
        val buf = synchronized(lock) {
            val f = front
            if (f != null) {
                front = null
                displayed = f
                fence = frontFence
                frontFence = 0L
                fresh = true
            }
            displayed
        } ?: return null
        if (fence != 0L) {
            GL32.glWaitSync(fence, 0, GL32.GL_TIMEOUT_IGNORED)
            GL32.glDeleteSync(fence)
        }
        return DisplayPipeline.Frame(buf.texture, buf.width, buf.height, buf.generation, fresh)
    }

    companion object {
        private val LOG = System.getProperty("nuvio.wayland.videoLog")?.toBoolean() ?: false
    }
}

/**
 * Consumer side of [UiPipeline]: wraps the shared UI textures for the window's
 * Skia context and draws them.
 *
 * Framebuffers do not cross contexts, so this binds each texture into its own
 * FBO and wraps that -- the same construction [WaylandVideoHost] uses for video,
 * for the same reason. Keyed by buffer generation, so a reallocation on the UI
 * thread invalidates the wrapper.
 */
class UiLayer(private val context: DirectContext) {
    private class Wrapper(
        val fbo: Int,
        val renderTarget: BackendRenderTarget,
        val surface: Surface,
    )

    private val wrappers = HashMap<Int, Wrapper>()

    fun draw(canvas: Canvas, frame: DisplayPipeline.Frame) {
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
            val surface = Surface.makeFromBackendRenderTarget(
                context, rt, SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
            ) ?: error("could not wrap UI texture for Skia (consumer)")
            evictStale(keep = frame.generation)
            // Raw GL behind Skia's back; its cached bindings are stale until told.
            context.resetGLAll()
            Wrapper(fbo, rt, surface)
        }

        if (frame.fresh) {
            // Both invalidations are mandatory, and are the same pair
            // WaylandVideoHost.compositeVideo documents: notifyContentWillChange
            // drops Skia's cached image of this surface (Surface.draw serves
            // that cache, and another context's writes are invisible to Skia,
            // so without it every present shows each buffer's first frame
            // forever), and the GL reset makes this context actually re-read
            // the shared texture instead of eliding the rebind.
            wrapper.surface.notifyContentWillChange(ContentChangeMode.DISCARD)
            context.resetGL(org.jetbrains.skia.GLBackendState.TEXTURE_BINDING)
        }
        // Surface.draw, not makeImageSnapshot: a snapshot is copy-on-write and
        // would cost a full-frame copy every time the UI changes.
        wrapper.surface.draw(canvas, 0, 0, null)
    }

    private fun evictStale(keep: Int) {
        val stale = wrappers.keys.filter { it < keep - 3 }
        for (g in stale) {
            wrappers.remove(g)?.let {
                it.surface.close()
                it.renderTarget.close()
                GL30.glDeleteFramebuffers(it.fbo)
            }
        }
    }

    fun close() {
        for (w in wrappers.values) {
            w.surface.close()
            w.renderTarget.close()
            GL30.glDeleteFramebuffers(w.fbo)
        }
        wrappers.clear()
    }
}
