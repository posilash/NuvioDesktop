package com.nuvio.wayland

/**
 * The consumer-facing surface of a video pipeline: whatever renders mpv
 * frames on its own thread and hands the UI thread GL textures to composite.
 * [VideoPipeline] is the OpenGL render-API implementation;
 * [VkGlDisplayPipeline] adapts the Vulkan one ([VideoPipelineVk]) by
 * importing its exported images into GL -- the scene stays a GL/Skia scene
 * either way.
 */
interface DisplayPipeline {
    /** A composited-ready frame: a GL texture in THIS context's share group. */
    class Frame(
        val texture: Int,
        val width: Int,
        val height: Int,
        val generation: Int,
        val fresh: Boolean,
    )

    /** Latest published frame; null before the first publish. */
    fun acquireFrame(): Frame?

    var probe: Boolean
    var onFrame: (() -> Unit)?
    val publishIntervalMs: Double

    /**
     * True when [acquireFrame] runs mpv's renderer on the calling thread:
     * the caller's Skia context must resetGLAll after a fresh frame
     * (Stremio's resetOpenGLState bracket).
     */
    val rendersOnConsumerThread: Boolean get() = false

    fun setTargetSize(width: Int, height: Int)
    fun start()
    fun stop()
    fun awaitReady()
    fun report(elapsedSeconds: Double): String
}
