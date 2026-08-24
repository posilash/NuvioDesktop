package com.nuvio.wayland

import org.lwjgl.glfw.GLFW.glfwGetProcAddress
import org.lwjgl.glfw.GLFW.glfwPostEmptyEvent
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Stremio's exact video model: mpv renders ON THE PRESENTING THREAD, inside
 * the pass that swaps, so the frame on screen is sampled AT PRESENT TIME
 * with constant near-zero latency. (QQuickFramebufferObject::Renderer's
 * render() is this method under another name.)
 *
 * Only possible in free-run mode: render() is non-blocking there (~2ms
 * measured). The separate video thread, triple buffering and fences existed
 * to keep the old BLOCKING render off the UI thread; in this mode that whole
 * apparatus -- and the variable arrival->present latency it introduced, felt
 * as a slight judder -- disappears.
 *
 * The consumer-side contract: after a fresh acquire the caller MUST
 * resetGLAll on its Skia context (mpv just ran a full renderer behind
 * Skia's back -- Stremio's resetOpenGLState() bracket, same reason), which
 * [WaylandVideoHost] does via [rendersOnConsumerThread].
 */
class EdtSampledPipeline(private val mpv: Mpv) : DisplayPipeline {
    @Volatile override var probe = false
    @Volatile override var onFrame: (() -> Unit)? = null
    override val publishIntervalMs: Double get() = 41.7
    override val rendersOnConsumerThread: Boolean get() = true

    @Volatile private var updatePending = false
    @Volatile private var targetW = 0
    @Volatile private var targetH = 0

    private var fbo = 0
    private var texture = 0
    private var texW = 0
    private var texH = 0
    private var generation = 0
    private var everRendered = false

    private var renders = 0L
    private var renderNanos = 0L
    private var fails = 0L

    override fun setTargetSize(width: Int, height: Int) {
        targetW = width
        targetH = height
    }

    /**
     * Created on the CALLING thread, which must own the window GL context
     * (the EDT calls this via the host init path): the render context binds
     * to the creating thread's GL, and all renders happen in [acquireFrame]
     * on that same thread.
     */
    override fun start() {
        mpv.createRenderContext(Mpv.MPV_RENDER_API_TYPE_OPENGL_NEXT) { name ->
            glfwGetProcAddress(name)
        }
        mpv.setUpdateCallback {
            updatePending = true
            onFrame?.invoke()
            glfwPostEmptyEvent()
        }
    }

    override fun awaitReady() {} // creation is synchronous on the caller

    override fun stop() {
        // The render context must die on its owning thread; Main tears down
        // on the EDT for this pipeline.
        mpv.freeRenderContext()
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo)
        if (texture != 0) GL11.glDeleteTextures(texture)
    }

    override fun report(elapsedSeconds: Double): String {
        val r = renders; renders = 0
        val n = renderNanos; renderNanos = 0
        return "sampled: renders/s=${(r / elapsedSeconds).toInt()} " +
            "renderAvg=%.1fms fails=$fails target=${targetW}x$targetH gen=$generation"
                .format(if (r > 0) n / 1e6 / r else 0.0)
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (texture != 0 && texW == w && texH == h) return
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo)
        if (texture != 0) GL11.glDeleteTextures(texture)
        texture = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
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
        texW = w
        texH = h
        generation++
        everRendered = false
    }

    override fun acquireFrame(): DisplayPipeline.Frame? {
        val w = targetW
        val h = targetH
        if (w <= 0 || h <= 0) return null
        ensureTarget(w, h)
        var fresh = false
        if (updatePending) {
            updatePending = false
            if (mpv.hasNewFrame() || !everRendered) {
                val t = System.nanoTime()
                val ret = mpv.render(fbo, w, h)
                renders++
                renderNanos += System.nanoTime() - t
                if (ret < 0) fails++ else { fresh = true; everRendered = true }
            }
        } else if (!everRendered) {
            // First composite can precede the first update; render whatever
            // mpv has so the surface is never a stale-texture flash.
            val ret = mpv.render(fbo, w, h)
            if (ret >= 0) { everRendered = true; fresh = true }
        }
        if (!everRendered) return null
        return DisplayPipeline.Frame(texture, w, h, generation, fresh)
    }
}
