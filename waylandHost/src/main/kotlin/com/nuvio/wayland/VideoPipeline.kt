package com.nuvio.wayland

import org.lwjgl.glfw.GLFW.glfwGetProcAddress
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPostEmptyEvent
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import java.util.concurrent.locks.LockSupport

/**
 * The video half of the host: a dedicated thread that owns a second, shared
 * GL context and everything mpv-render on it.
 *
 * This is the shape the render API is designed around. `mpv_render_context_render`
 * blocks until the frame's presentation time -- that *is* mpv's display sync,
 * the machinery behind vo=gpu-next's smoothness -- so the thread that calls it
 * must be allowed to sleep. An earlier revision called it from the UI thread
 * with blocking disabled and re-implemented the pacing by hand; the measured
 * result was a third of frames dropped and mpv estimating the display at 5 Hz.
 * Here mpv paces its own thread, and the UI thread only ever samples the last
 * published texture.
 *
 * Buffers are triple: one being rendered into, one published, one possibly
 * still being read by the UI thread. Textures are shared between the two
 * contexts (same share group); framebuffers are not shareable, so each side
 * wraps the texture in its own. A GL fence accompanies every publish -- the
 * consumer must wait on it before sampling, since renders happen on another
 * context whose commands are not otherwise ordered with the consumer's.
 */
class VideoPipeline(
    private val mpv: Mpv,
    private val videoWindow: Long,
) {
    class Buffer {
        var texture = 0
        var fbo = 0
        var width = 0
        var height = 0
        /** Identity for consumer-side wrapper caches; bumped on reallocation. */
        var generation = 0
    }

    private val lock = Object()
    private val buffers = Array(3) { Buffer() }
    private var front: Buffer? = null      // latest published, not yet taken
    private var displayed: Buffer? = null  // held by the consumer
    private var rendering: Buffer? = null  // owned by the video thread
    private var frontFence = 0L

    // Requested render size, in framebuffer pixels. Written by the UI side
    // (video rect reports), read by the video thread.
    @Volatile private var targetWidth = 0
    @Volatile private var targetHeight = 0

    @Volatile private var running = true
    @Volatile private var updatePending = false
    private var thread: Thread? = null
    private val ready = java.util.concurrent.CountDownLatch(1)

    // Telemetry, read by report().
    @Volatile private var renders = 0L
    @Volatile private var renderNanos = 0L
    @Volatile private var renderFails = 0L
    @Volatile private var lastGeneration = 0

    /** Per-publish diagnostics; costs a readback per frame, so opt-in. */
    @Volatile var probe = false

    /** Called (from any thread) after each publish; wired to wake the host loop. */
    @Volatile var onFrame: (() -> Unit)? = null

    // Source cadence measured at the publish point, where mpv's own pacing
    // sets it -- present times are downstream and get contaminated by the
    // very stalls a scheduler needs this number to avoid.
    @Volatile var publishIntervalMs: Double = 41.7
        private set
    private var lastPublishNs = 0L

    private fun notePublish() {
        val now = System.nanoTime()
        if (lastPublishNs != 0L) {
            val ms = (now - lastPublishNs) / 1e6
            if (ms in 8.0..120.0) publishIntervalMs += (ms - publishIntervalMs) * 0.1
        }
        lastPublishNs = now
    }

    fun setTargetSize(width: Int, height: Int) {
        if (width == targetWidth && height == targetHeight) return
        targetWidth = width
        targetHeight = height
        // A resize should re-render the current frame even when paused.
        updatePending = true
        thread?.let { LockSupport.unpark(it) }
    }

    fun start() {
        thread = Thread({ run() }, "nuvio-video").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.let { LockSupport.unpark(it) }
        thread?.join(3000)
    }

    /** Blocks until the mpv render context exists; loadfile before it fails. */
    fun awaitReady() {
        ready.await()
        initError?.let { throw IllegalStateException("video pipeline failed to start", it) }
    }

    /** Per-second telemetry line, or null. */
    fun report(elapsedSeconds: Double): String {
        val r = renders; renders = 0
        val ns = renderNanos; renderNanos = 0
        val f = renderFails; renderFails = 0
        val avgMs = if (r > 0) ns / 1e6 / r else 0.0
        return "pipeline: renders/s=%.0f renderAvg=%.1fms fails=%d target=%dx%d gen=%d"
            .format(r / elapsedSeconds, avgMs, f, targetWidth, targetHeight, lastGeneration)
    }

    @Volatile private var initError: Throwable? = null

    private fun run() {
        try {
            glfwMakeContextCurrent(videoWindow)
            GL.createCapabilities()

            // The render context must be created against this thread's context:
            // mpv resolves and uses GL from the thread that creates it, and all
            // later render calls happen here.
            mpv.createRenderContext(Mpv.MPV_RENDER_API_TYPE_OPENGL_NEXT) { name ->
                glfwGetProcAddress(name)
            }
            val self = Thread.currentThread()
            mpv.setUpdateCallback {
                updatePending = true
                LockSupport.unpark(self)
            }
        } catch (t: Throwable) {
            initError = t
            running = false
            return
        } finally {
            ready.countDown()
        }

        try {
            loop()
        } finally {
            // The render context is bound to this thread's GL context and must
            // die before it, whatever ended the loop.
            mpv.freeRenderContext()
            synchronized(lock) {
                if (frontFence != 0L) { GL32.glDeleteSync(frontFence); frontFence = 0L }
            }
            for (b in buffers) releaseBuffer(b)
        }
    }

    private fun loop() {
        while (running) {
            if (!updatePending) {
                // Parked until mpv's update callback or a resize. The timeout
                // is only a shutdown/robustness backstop.
                LockSupport.parkNanos(250_000_000L)
                continue
            }
            updatePending = false

            val w = targetWidth
            val h = targetHeight
            if (w <= 0 || h <= 0) continue
            if (!mpv.hasNewFrame() && !needsRealloc(w, h)) continue

            val buf = acquireBuffer(w, h) ?: continue

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, buf.fbo)
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)

            val t = System.nanoTime()
            val ret = mpv.render(buf.fbo, w, h) // blocks until presentation time: mpv's pacing
            renders++
            renderNanos += System.nanoTime() - t
            if (ret < 0) renderFails++

            if (probe) {
                // Producer-side truth: what is actually in the texture the
                // moment it is published, read back on the thread and context
                // that wrote it.
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, buf.fbo)
                val px = java.nio.ByteBuffer.allocateDirect(4)
                GL11.glReadPixels(w / 2, h / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px)
                val v = (px.get(0).toInt() and 0xFF shl 16) or
                    (px.get(1).toInt() and 0xFF shl 8) or (px.get(2).toInt() and 0xFF)
                println(
                    "[wayland-video] publish: buf=${buffers.indexOf(buf)} gen=${buf.generation} " +
                        "tex=${buf.texture} center=%06x ret=$ret".format(v),
                )
            }

            val fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            // The fence must reach the GPU before another context waits on it.
            GL11.glFlush()

            synchronized(lock) {
                if (frontFence != 0L) GL32.glDeleteSync(frontFence)
                front = buf
                frontFence = fence
                rendering = null
            }
            lastGeneration = buf.generation
            notePublish()
            onFrame?.invoke()
            // Wake the host loop even if it is idle in glfwWaitEventsTimeout.
            glfwPostEmptyEvent()
        }
    }

    private fun needsRealloc(w: Int, h: Int): Boolean {
        val f = synchronized(lock) { front ?: displayed }
        return f == null || f.width != w || f.height != h
    }

    /** Pick the buffer that is neither published nor being displayed. */
    private fun acquireBuffer(w: Int, h: Int): Buffer? {
        val buf = synchronized(lock) {
            buffers.firstOrNull { it !== front && it !== displayed }?.also { rendering = it }
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
            buf.width = w
            buf.height = h
            buf.generation = ++generationCounter
        }
        return buf
    }

    private var generationCounter = 0

    private fun releaseBuffer(b: Buffer) {
        if (b.fbo != 0) { GL30.glDeleteFramebuffers(b.fbo); b.fbo = 0 }
        if (b.texture != 0) { GL11.glDeleteTextures(b.texture); b.texture = 0 }
        b.width = 0; b.height = 0
    }

    class DisplayFrame(val buffer: Buffer, val fresh: Boolean)

    /**
     * Latest published frame, for the consumer. Called on the UI thread with
     * its own GL context current.
     *
     * Transfers the front buffer to `displayed` (freeing the previous one for
     * rendering) and performs the cross-context fence wait -- server-side, so
     * it costs a queue token, not a stall. [DisplayFrame.fresh] is true when
     * this call picked up a new publish: the consumer must then invalidate
     * whatever caches assume the texture's content (or GL's view of it) is
     * unchanged. The buffer stays valid until the next call. Null when nothing
     * was ever published.
     */
    fun acquireDisplayFrame(): DisplayFrame? {
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
        return DisplayFrame(buf, fresh)
    }
}
