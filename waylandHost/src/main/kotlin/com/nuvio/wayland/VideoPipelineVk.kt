package com.nuvio.wayland

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdKHR
import org.lwjgl.vulkan.KHRExternalSemaphoreFd
import org.lwjgl.vulkan.KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkFormatProperties
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkQueueFamilyProperties
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import org.lwjgl.vulkan.VkSemaphoreGetFdInfoKHR
import org.lwjgl.vulkan.VkSubmitInfo

/**
 * The Vulkan sibling of [VideoPipeline]: a dedicated thread that owns a
 * VkDevice and an mpv "vulkan" render context, and renders each frame into an
 * exportable VkImage.
 *
 * Same pacing contract as the GL pipeline -- mpv_render_context_render blocks
 * until the frame's presentation time, so it must own its thread -- but the
 * handoff to the consumer is different in kind, not just API. GL textures were
 * shared through a share group; VkImages are shared through the kernel: every
 * buffer's device memory and its render-done semaphore are exported as opaque
 * fds (VK_KHR_external_memory_fd / VK_KHR_external_semaphore_fd), which a GL
 * consumer imports once per allocation via GL_EXT_memory_object_fd /
 * GL_EXT_semaphore_fd and then treats like any other texture. The cross-API
 * fence-wait equivalent is glWaitSemaphoreEXT on the imported semaphore, which
 * needs the image's layout -- that is why [Buffer.outLayout] (what mpv left
 * the image in) is tracked per frame and published with it.
 *
 * Why this exists at all: mpv imports the device instead of creating one, so
 * decoded nvdec frames and the render target live on the same VkDevice and
 * never cross PCIe. The GL path can't do that -- libmpv's GL backend has no
 * export surface, so a GL host either draws mpv into its own FBO (fine, the
 * current pipeline) or copies. This one renders on mpv's terms and exports on
 * the consumer's.
 *
 * Semaphore hygiene, because binary semaphores are unforgiving: mpv signals a
 * buffer's semaphore every render, and each signal must be matched by exactly
 * one wait. A consumer that takes a fresh frame owns that wait
 * (glWaitSemaphoreEXT). A published frame that gets *replaced* before any
 * consumer took it still carries a pending signal, so the render thread drains
 * it with an empty queue submission before reusing the buffer -- otherwise the
 * second signal is a validation error and, on some drivers, a hang.
 *
 * The protocol is two-directional. The render-done semaphore above orders
 * GL reads after mpv's writes; a second per-buffer semaphore ([Buffer
 * .glDoneSemaphore], "glDone") orders mpv's *next* write after GL's reads.
 * Without it there is a write-after-read hazard on wrap-around: when a buffer
 * rotates back to the render thread, mpv's queue starts overwriting the image
 * while the consumer's earlier sampling commands may still be executing --
 * periodic corruption that shows as flicker. The consumer signals glDone (via
 * its imported GL twin, plus a flush) when a fresh frame replaces the buffer
 * it was displaying, then calls [notifyGlDone]; the render thread submits an
 * empty queue batch waiting that semaphore before handing the image back to
 * mpv. Same-queue submission order then carries the dependency into mpv's own
 * submissions, so no CPU wait is needed. mpv_vulkan_fbo.wait_semaphore is NOT
 * used for this: the fork rewraps the VkImage every frame (buffers
 * alternate), and libplacebo's release-with-semaphore path early-returns on a
 * freshly wrapped ("unheld") image, silently dropping the wait.
 *
 * The handoff has a race the [retiring] slot closes: the instant
 * acquireDisplayFrame() switches [displayed], the old buffer would be free
 * for the render thread to pick -- but the consumer can only signal glDone
 * *after* that call returns. So the replaced buffer parks in [retiring],
 * excluded from the rotation, until notifyGlDone() marks its signal pending.
 */
class VideoPipelineVk(private val mpv: Mpv) {

    /**
     * Render on someone else's device instead of creating one.
     *
     * Set before [start] to share the presenter's device, so mpv's frames, the
     * scene Skia draws and the swapchain all live on one VkDevice and nothing
     * has to be exported and imported between them. The device must already
     * carry the feature chain and extensions mpv needs, which is why the
     * presenter creates it with them.
     */
    class SharedDevice(
        val instance: VkInstance,
        val physicalDevice: VkPhysicalDevice,
        val device: VkDevice,
        val queue: VkQueue,
        val queueFamily: Int,
        val featuresChain: Long,
        val extensions: List<String>,
        /**
         * Guards the queue. Once mpv, Skia and the presenter share one device
         * they share its queue, and vkQueueSubmit demands external
         * synchronisation -- render_vk.h says so outright: the queues "must not
         * be submitted to from another thread while mpv_render_context_render()
         * runs, unless lock_queue and unlock_queue are provided".
         */
        val queueLock: java.util.concurrent.locks.ReentrantLock?,
    )

    var sharedDevice: SharedDevice? = null

    /**
     * The colour space this pipeline renders for, set from the source by the
     * host. Volatile because the video thread reads it while the host writes.
     */
    @Volatile
    var targetColorSpace: TargetColorSpace = TargetColorSpace.SDR
        set(value) {
            field = value
            // The depth mpv renders at follows the target: the buffers are
            // reallocated on the next frame because needsRealloc sees it.
            val f = chooseRenderFormat(value)
            if (f != renderFormat) {
                println("vk-pipeline: render format $renderFormat -> $f for this target")
                renderFormat = f
            }
        }

    /** Runs [block] holding the shared queue lock, if there is one. */
    private inline fun <T> withQueue(block: () -> T): T {
        val l = sharedDevice?.queueLock ?: return block()
        l.lock()
        try { return block() } finally { l.unlock() }
    }

    companion object {
        /** VK_FORMAT_R8G8B8A8_UNORM: what GL_RGBA8 imports as, both ways. */
        val FORMAT = VK_FORMAT_R8G8B8A8_UNORM

        /**
         * COLOR_ATTACHMENT is what mpv requires, STORAGE is what keeps it off
         * its slow fallback paths, SAMPLED is what the GL consumer needs.
         * The same value is passed in every mpv_vulkan_fbo, which must state
         * the usage the image was *created* with.
         *
         * TRANSFER_DST is load-bearing for performance: whenever the target
         * aspect ratio differs from the video's, libplacebo has to clear the
         * letterbox bars, and without TRANSFER_DST it cannot use
         * vkCmdClearColorImage and falls into a fallback that costs ~20ms of
         * CPU-blocking time per frame *regardless of target size* (measured:
         * 1272x687 target for a 16:9 clip renders in ~21ms without the bit
         * and ~1ms with it; exact-16:9 targets never hit the path at all).
         * TRANSFER_SRC matches the fork's own test client and keeps
         * screenshot-style blits off slow paths too.
         */
        val USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
            VK_IMAGE_USAGE_STORAGE_BIT or
            VK_IMAGE_USAGE_SAMPLED_BIT or
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
            VK_IMAGE_USAGE_TRANSFER_DST_BIT

        // Both mandatory: the export path here needs them, and mpv's zero-copy
        // nvdec interop refuses to load unless the device has them enabled
        // *and* they are reported in mpv_vulkan_init_params.extensions.
        val DEVICE_EXTENSIONS = listOf(
            KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
            KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        )
    }

    class Buffer {
        var image = VK_NULL_HANDLE
        var memory = VK_NULL_HANDLE
        /** Exact allocation size; the GL import must state it verbatim. */
        var allocationSize = 0L
        /** Render-done semaphore mpv signals; consumer waits via its fd twin. */
        var semaphore = VK_NULL_HANDLE
        /** Opaque fd for [memory]; owned by the pipeline until a consumer
         * imports it (GL import consumes the fd -- set [fdsOwnedByConsumer]). */
        var memoryFd = -1
        /** Opaque fd for [semaphore]; same ownership rule. */
        var semaphoreFd = -1
        /** GL-done semaphore: the consumer signals it (through the imported
         * GL twin) when it stops displaying this buffer; the render thread
         * waits it before mpv writes into the image again. */
        var glDoneSemaphore = VK_NULL_HANDLE
        /** Opaque fd for [glDoneSemaphore]; same ownership rule. */
        var glDoneSemaphoreFd = -1
        /** True between the consumer's glDone signal (set on the consumer
         * thread, after its flush) and the render thread's matching wait.
         * A fresh buffer never sampled stays false and needs no wait. */
        @Volatile var glDoneOwed = false
        /** Layout mpv left the image in after the last render; the GL side
         * passes it to glWaitSemaphoreEXT so the driver can transition. */
        var outLayout = VK_IMAGE_LAYOUT_UNDEFINED
        var width = 0
        var height = 0
        /** The VkFormat it was created with; the target's depth can change it. */
        var format = 0
        /** Identity for consumer-side import caches; bumped on reallocation. */
        var generation = 0
        /** Consumer sets this after importing the fds, so the pipeline does
         * not close what the GL driver now owns. */
        @Volatile var fdsOwnedByConsumer = false
        /** True while the buffer carries an unwaited semaphore signal. */
        internal var signalPending = false
    }

    // -- Vulkan state, created and destroyed on the video thread. ------------

    private lateinit var instance: VkInstance
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var queue: VkQueue
    private var queueFamily = -1
    private var drainFence = VK_NULL_HANDLE

    // The feature chain is heap-allocated because mpv_vulkan_init_params keeps
    // a pointer to it: it must outlive the render context, not a stack frame.
    private var f13: VkPhysicalDeviceVulkan13Features? = null
    private var f12: VkPhysicalDeviceVulkan12Features? = null
    private var f11: VkPhysicalDeviceVulkan11Features? = null
    private var features2: VkPhysicalDeviceFeatures2? = null

    @Volatile var deviceName: String = ""
        private set

    // -- Buffer handoff, same triple-buffer protocol as VideoPipeline. ------

    private val lock = Object()
    // Diagnostic lever: -Dnuvio.wayland.vkBuffers=1 removes rotation (and
    // therefore every wrap-around hazard) to bisect corruption sources.
    //
    // Five, not three. The consumer holds two at all times -- one displayed,
    // one retiring until its glDone signal lands -- so three left exactly one
    // for rendering, and mpv does not deliver at a steady 24fps: it publishes
    // in bursts (measured: 25 back-to-back publishes per 5s). A burst of two
    // had nowhere to go, so mpv blocked ~117ms waiting for a buffer, fell
    // further behind, and burst harder to catch up. Frames it could not place
    // were dropped -- 71 of them in ten seconds of a 1080p stream. Headroom
    // absorbs the burst instead; at 2560x1440 each buffer is ~14MB.
    private val buffers = Array(
        System.getProperty("nuvio.wayland.vkBuffers")?.toIntOrNull()?.coerceIn(1, 8) ?: 5,
    ) { Buffer() }
    private var front: Buffer? = null      // latest published, not yet taken
    private var displayed: Buffer? = null  // held by the consumer
    private var rendering: Buffer? = null  // owned by the video thread
    // Replaced-as-displayed, awaiting the consumer's glDone signal; excluded
    // from the render rotation until notifyGlDone() flips glDoneOwed.
    private var retiring: Buffer? = null

    @Volatile private var targetWidth = 0
    @Volatile private var targetHeight = 0

    @Volatile private var running = true
    @Volatile private var updatePending = false
    private var thread: Thread? = null
    private val ready = CountDownLatch(1)
    @Volatile private var initError: Throwable? = null

    // Telemetry, read by report().
    @Volatile private var renders = 0L
    @Volatile private var renderNanos = 0L
    @Volatile private var renderFails = 0L
    /** glDone waits submitted; should track renders/s once playback is
     * steady, and reads 0 if the consumer ever stops signalling back --
     * i.e. it is the write-after-read protection's liveness check. */
    @Volatile private var glDoneWaits = 0L
    @Volatile private var lastGeneration = 0
    @Volatile var totalRenders = 0L
        private set

    /** Called (from the video thread) after each publish. */
    @Volatile var onFrame: (() -> Unit)? = null

    fun setTargetSize(width: Int, height: Int) {
        if (width == targetWidth && height == targetHeight) return
        targetWidth = width
        targetHeight = height
        updatePending = true
        thread?.let { LockSupport.unpark(it) }
    }

    fun start() {
        thread = Thread({ run() }, "nuvio-video-vk").apply {
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
        initError?.let { throw IllegalStateException("vulkan pipeline failed to start", it) }
    }

    /** Per-second telemetry line. */
    fun report(elapsedSeconds: Double): String {
        val r = renders; renders = 0
        val ns = renderNanos; renderNanos = 0
        val f = renderFails; renderFails = 0
        val gd = glDoneWaits; glDoneWaits = 0
        val avgMs = if (r > 0) ns / 1e6 / r else 0.0
        return "vk-pipeline: renders/s=%.0f renderAvg=%.1fms fails=%d glDone/s=%.0f target=%dx%d gen=%d"
            .format(r / elapsedSeconds, avgMs, f, gd / elapsedSeconds, targetWidth, targetHeight, lastGeneration)
    }

    /** What a GL consumer needs to import one buffer, frozen for printing. */
    data class ExportInfo(
        val index: Int,
        val generation: Int,
        val width: Int,
        val height: Int,
        val memoryFd: Int,
        val allocationSize: Long,
        val semaphoreFd: Int,
        val glDoneSemaphoreFd: Int,
        val outLayout: Int,
    )

    fun exportSnapshot(): List<ExportInfo> = synchronized(lock) {
        buffers.mapIndexed { i, b ->
            ExportInfo(i, b.generation, b.width, b.height,
                b.memoryFd, b.allocationSize, b.semaphoreFd, b.glDoneSemaphoreFd, b.outLayout)
        }
    }

    // -- Video thread. -------------------------------------------------------

    private fun run() {
        try {
            // LWJGL's VkInstance/VkDevice constructors enumerate every device
            // extension (~230 on NVIDIA, ~60KB of VkExtensionProperties) on
            // the thread-local MemoryStack, whose 64KB default overflows once
            // any caller frame is also pushed. Must be raised before this
            // thread's stack is lazily created, i.e. before any stackPush().
            if (org.lwjgl.system.Configuration.STACK_SIZE.get(64) < 512) {
                org.lwjgl.system.Configuration.STACK_SIZE.set(512)
            }
            initVulkan()
            // Unlike GL there is no thread-bound context, but the render
            // context is still created here so all mpv render calls -- and
            // every queue submission they imply -- stay on this thread.
            mpv.createRenderContextVulkan(
                instance = instance.address(),
                physDevice = physicalDevice.address(),
                device = device.address(),
                // From LWJGL's own loader (libvulkan.so.1): the loader that
                // created the instance, which is the one mpv must resolve
                // through.
                getInstanceProcAddr = VK.getFunctionProvider()
                    .getFunctionAddress("vkGetInstanceProcAddr"),
                features = sharedDevice?.featuresChain ?: features2!!.address(),
                extensions = sharedDevice?.extensions ?: DEVICE_EXTENSIONS,
                queueFamily = queueFamily,
                queueLock = sharedDevice?.queueLock,
            )
            val self = Thread.currentThread()
            mpv.setUpdateCallback {
                updatePending = true
                LockSupport.unpark(self)
            }
        } catch (t: Throwable) {
            initError = t
            running = false
            // If the render context was created before the failure (e.g. the
            // update-callback hookup threw), it must be freed before the
            // device it imported is destroyed and before the feature chain it
            // still points at is released -- otherwise mpv keeps referencing
            // freed memory. No-op when the context never came up.
            mpv.freeRenderContext()
            destroyVulkan()
            return
        } finally {
            ready.countDown()
        }

        try {
            loop()
        } finally {
            // Teardown order matters: mpv's context submits to our queue, so
            // it must be gone before the device it was imported into.
            mpv.freeRenderContext()
            destroyVulkan()
        }
    }

    private fun loop() {
        while (running) {
            if (!updatePending) {
                LockSupport.parkNanos(250_000_000L)
                continue
            }
            updatePending = false

            val w = targetWidth
            val h = targetHeight
            if (w <= 0 || h <= 0) continue
            if (!mpv.hasNewFrame() && !needsRealloc(w, h)) continue

            // Starvation, measured: a render that cannot start because every
            // buffer is still with the consumer is the one thing that shows up
            // as a late frame with nothing slow anywhere.
            val buf = acquireBuffer(w, h)
            if (buf == null) {
                if (starvedSinceNs == 0L) starvedSinceNs = System.nanoTime()
                continue
            }
            if (starvedSinceNs != 0L) {
                waitedForBufferNs += System.nanoTime() - starvedSinceNs
                waitedForBufferCount++
                starvedSinceNs = 0L
            }

            // Known-noisy: the fork caches exactly one wrapped target
            // (libmpv_vk_pl.c p->target) and rewraps whenever the VkImage
            // changes, so alternating buffers logs libplacebo's "Attempting
            // to release an unheld image?" once per frame -- the release that
            // hands a *freshly wrapped* image over has no prior hold to pair
            // with. Harmless here: layout is UNDEFINED and nothing needs
            // preserving, so the unpaired release changes no behaviour.
            val target = targetColorSpace
            val t = System.nanoTime()
            val (ret, outLayout) = mpv.renderVulkan(
                Mpv.VulkanFrame(
                    image = buf.image,
                    format = renderFormat,
                    w = w,
                    h = h,
                    usage = USAGE,
                    // Contents never need preserving: mpv repaints the full
                    // target, and UNDEFINED lets the driver skip a transition.
                    layout = VK_IMAGE_LAYOUT_UNDEFINED,
                    outLayout = 0, // mpv picks and reports back
                    signalSemaphore = buf.semaphore,
                    // What this target is, so mpv renders for it instead of
                    // assuming sRGB and tone-mapping HDR away. Zero when the
                    // file is SDR or the surface cannot carry its colour space,
                    // which is exactly the old behaviour.
                    primaries = target.primaries,
                    transfer = target.transfer,
                    minLuma = target.minLuma,
                    maxLuma = target.maxLuma,
                ),
            )
            renders++
            totalRenders++
            renderNanos += System.nanoTime() - t
            // Whether mpv submitted the signal on failure is unspecified, so
            // the pending flag is set either way; the drain's bounded wait is
            // what makes the pessimistic assumption safe.
            buf.signalPending = true
            if (ret < 0) {
                renderFails++
                synchronized(lock) { rendering = null }
                continue
            }
            buf.outLayout = outLayout

            synchronized(lock) {
                // A replaced-but-never-taken front keeps its pending signal;
                // acquireBuffer() drains it before the buffer renders again.
                if (front != null) untakenFronts++
                front = buf
                rendering = null
            }
            lastGeneration = buf.generation
            notePublish(System.nanoTime())
            onFrame?.invoke()
        }
    }

    // Publish cadence, measured HERE rather than at the far end of the host
    // loop. The host's own histogram cannot tell "mpv handed us a frame late"
    // apart from "we were slow to present it", and those have opposite fixes.
    private val publishBuckets = IntArray(12)
    private var lastPublishNs = 0L
    private var publishReportNs = 0L
    private var waitedForBufferNs = 0L
    private var waitedForBufferCount = 0L
    private var starvedSinceNs = 0L
    // A drain is a CPU-blocking fence wait, and it happens when a published
    // frame was never taken. Counting it separates "the consumer is not
    // collecting" from every other reason a render could be late.
    private var drains = 0L
    private var drainNanos = 0L
    private var untakenFronts = 0L

    private fun notePublish(now: Long) {
        if (lastPublishNs != 0L) {
            val ms = (now - lastPublishNs) / 1e6
            publishBuckets[(ms / 6.06).toInt().coerceIn(0, publishBuckets.size - 1)]++
        }
        lastPublishNs = now
        if (now - publishReportNs > 5_000_000_000L) {
            if (publishReportNs != 0L) {
                val body = publishBuckets.withIndex().filter { it.value > 0 }
                    .joinToString(" ") { "${it.index}v=${it.value}" }
                println(
                    "[wayland-video] publish(vsyncs): $body " +
                        "bufferWaits=$waitedForBufferCount " +
                        "drains=$drains drainMs=%.0f untaken=$untakenFronts ".format(
                            drainNanos / 1e6,
                        ) +
                        "avgWait=%.1fms".format(
                            if (waitedForBufferCount > 0) {
                                waitedForBufferNs / 1e6 / waitedForBufferCount
                            } else {
                                0.0
                            },
                        ),
                )
                publishBuckets.fill(0)
                waitedForBufferNs = 0
                waitedForBufferCount = 0
                drains = 0; drainNanos = 0; untakenFronts = 0
            }
            publishReportNs = now
        }
    }

    private fun needsRealloc(w: Int, h: Int): Boolean {
        // Size only. A format change is handled where the buffer is picked, so
        // testing it here just kept this true until every buffer had cycled --
        // rendering on every pass rather than on new frames.
        val f = synchronized(lock) { front ?: displayed }
        return f == null || f.width != w || f.height != h
    }

    /**
     * The format mpv renders into.
     *
     * 8-bit is enough for an SDR target and is what this always used, but it
     * is not enough for an HDR one: PQ spends its code points on the low end,
     * and eight bits of it bands visibly in dark scenes. A standalone
     * gpu-next window uses rgb10a2 for exactly this reason.
     *
     * Chosen against what the device actually supports for [USAGE] rather than
     * assumed -- STORAGE on a packed 10-bit format is optional in Vulkan, and
     * asking for an unsupported combination fails image creation.
     */
    @Volatile
    var renderFormat = FORMAT
        private set

    private fun chooseRenderFormat(target: TargetColorSpace): Int {
        if (!target.isHdr) return FORMAT
        for (f in intArrayOf(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_R16G16B16A16_SFLOAT)) {
            if (supportsRenderFormat(f)) return f
        }
        return FORMAT
    }

    private fun supportsRenderFormat(format: Int): Boolean {
        if (!::physicalDevice.isInitialized) return false
        stackPush().use { s ->
            val props = VkFormatProperties.calloc(s)
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props)
            // TRANSFER_SRC/DST as format features are Vulkan 1.1.
            val need = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT or
                VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT or
                VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT or
                VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT or
                VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT
            return props.optimalTilingFeatures() and need == need
        }
    }

    /** Pick the buffer that is neither published nor being displayed. */
    private fun acquireBuffer(w: Int, h: Int): Buffer? {
        val buf = synchronized(lock) {
            buffers.firstOrNull {
                it !== front && it !== displayed &&
                    // A retiring buffer is untouchable until the consumer's
                    // glDone signal is in flight (see acquireDisplayFrame).
                    (it !== retiring || it.glDoneOwed)
            }?.also {
                if (it === retiring) retiring = null
                rendering = it
            }
        } ?: return null
        if (buf.signalPending) {
            // Dropped frame: its signal was never consumed. Unsignal on the
            // GPU before mpv signals again (and before any destroy).
            drainSemaphore(buf.semaphore)
            buf.signalPending = false
        }
        // Format belongs here as much as size does. Without it a buffer kept
        // its old 8-bit image while mpv was handed the new format in the fbo,
        // so mpv rendered one thing into another -- and needsRealloc stayed
        // true forever, which made the thread render on every pass instead of
        // on new frames. That pair is the flicker, at double the source rate.
        if (buf.image == VK_NULL_HANDLE || buf.width != w || buf.height != h ||
            buf.format != renderFormat
        ) {
            releaseBuffer(buf)
            allocateBuffer(buf, w, h)
        }
        if (buf.glDoneOwed) {
            // The consumer finished with this image and signaled glDone (its
            // flush happened before the flag was set, so the signal is on its
            // way to the GPU). Queue a wait so mpv's upcoming write is
            // ordered after the consumer's reads -- the WAR hazard fix.
            waitGlDone(buf.glDoneSemaphore)
            buf.glDoneOwed = false
            glDoneWaits++
        }
        return buf
    }

    class DisplayFrame(val buffer: Buffer, val fresh: Boolean, val retired: Buffer?)

    /**
     * Latest published frame, for the consumer.
     *
     * When [DisplayFrame.fresh] is true the consumer inherits the semaphore
     * wait: it must glWaitSemaphoreEXT (or vkQueueSubmit-wait) on the buffer's
     * semaphore, with [Buffer.outLayout], before sampling -- the pipeline
     * stops tracking that signal the moment the frame is handed over. A
     * generation change means the fds are new and must be re-imported.
     *
     * When [DisplayFrame.retired] is non-null the consumer owes that buffer a
     * glDone signal: it must signal the buffer's glDone semaphore (GL twin),
     * flush so the signal reaches the GPU, then call [notifyGlDone]. Until
     * then the buffer sits in [retiring], excluded from the render rotation,
     * so mpv cannot overwrite it under the consumer's still-executing reads.
     */
    fun acquireDisplayFrame(): DisplayFrame? {
        var fresh = false
        var retired: Buffer? = null
        val buf = synchronized(lock) {
            val f = front
            if (f != null) {
                front = null
                val prev = displayed
                if (prev != null && prev !== f) {
                    retiring = prev
                    retired = prev
                }
                displayed = f
                f.signalPending = false // the consumer owns the wait now
                fresh = true
            }
            displayed
        } ?: return null
        return DisplayFrame(buf, fresh, retired)
    }

    /**
     * Consumer callback: the glDone signal for [b] has been issued and
     * flushed. Only now may the render thread wait it -- enqueueing a GPU
     * wait for a signal that never reaches the queue would hang the device.
     * The unpark retries any render skipped while [b] was excluded.
     */
    fun notifyGlDone(b: Buffer) {
        b.glDoneOwed = true
        updatePending = true
        thread?.let { LockSupport.unpark(it) }
    }

    // -- Vulkan plumbing. ----------------------------------------------------

    private fun vkCheck(r: Int, what: String) {
        check(r == VK_SUCCESS) { "$what -> VkResult $r" }
    }

    private fun initVulkan() {
        sharedDevice?.let { shared ->
            instance = shared.instance
            physicalDevice = shared.physicalDevice
            device = shared.device
            queue = shared.queue
            queueFamily = shared.queueFamily
            stackPush().use { s ->
                val props = VkPhysicalDeviceProperties.calloc(s)
                vkGetPhysicalDeviceProperties(physicalDevice, props)
                deviceName = props.deviceNameString()
                val fci = VkFenceCreateInfo.calloc(s)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                val pf = s.mallocLong(1)
                vkCheck(vkCreateFence(device, fci, null, pf), "vkCreateFence")
                drainFence = pf.get(0)
            }
            println("[wayland-video] vk: sharing the presenter's device ($deviceName)")
            return
        }
        stackPush().use { s ->
            // Instance: 1.3, no layers, no instance extensions -- headless,
            // and mpv/libplacebo need nothing beyond the loader entry point.
            val app = VkApplicationInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(s.ASCII("nuvio-wayland"))
                .apiVersion(VK13.VK_API_VERSION_1_3)
            val ici = VkInstanceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(app)
            val pp = s.mallocPointer(1)
            vkCheck(vkCreateInstance(ici, null, pp), "vkCreateInstance")
            instance = VkInstance(pp.get(0), ici)

            val count = s.mallocInt(1)
            vkCheck(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices")
            check(count.get(0) > 0) { "no Vulkan devices" }
            val devs = s.mallocPointer(count.get(0))
            vkCheck(vkEnumeratePhysicalDevices(instance, count, devs), "vkEnumeratePhysicalDevices")
            physicalDevice = VkPhysicalDevice(devs.get(0), instance)

            val props = VkPhysicalDeviceProperties.calloc(s)
            vkGetPhysicalDeviceProperties(physicalDevice, props)
            deviceName = props.deviceNameString()

            // One family that does graphics + compute, like the C reference:
            // mpv wants all three queue kinds and this host's NVIDIA family 0
            // is all of them.
            val qc = s.mallocInt(1)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, qc, null)
            val qprops = VkQueueFamilyProperties.calloc(qc.get(0), s)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, qc, qprops)
            queueFamily = (0 until qc.get(0)).firstOrNull { i ->
                val flags = qprops.get(i).queueFlags()
                flags and VK_QUEUE_GRAPHICS_BIT != 0 && flags and VK_QUEUE_COMPUTE_BIT != 0
            } ?: throw IllegalStateException("no graphics+compute queue family")

            // The export extensions are load-bearing twice over (frame export
            // here, zero-copy nvdec inside mpv), so fail loudly if absent
            // rather than let hwdec silently degrade.
            val extCount = s.mallocInt(1)
            vkCheck(
                vkEnumerateDeviceExtensionProperties(physicalDevice, null as ByteBuffer?, extCount, null),
                "vkEnumerateDeviceExtensionProperties",
            )
            val eprops = VkExtensionProperties.calloc(extCount.get(0))
            try {
                vkCheck(
                    vkEnumerateDeviceExtensionProperties(physicalDevice, null as ByteBuffer?, extCount, eprops),
                    "vkEnumerateDeviceExtensionProperties",
                )
                val available = (0 until extCount.get(0)).map { eprops.get(it).extensionNameString() }.toSet()
                for (e in DEVICE_EXTENSIONS) {
                    check(e in available) { "$deviceName lacks $e" }
                }
            } finally {
                eprops.free()
            }

            // The feature chain mpv (via libplacebo) requires; creating the
            // device without it makes mpv_render_context_create fail with
            // MPV_ERROR_UNSUPPORTED. Heap-allocated: mpv holds the pointer for
            // the life of the render context.
            f13 = VkPhysicalDeviceVulkan13Features.calloc()
                .sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .synchronization2(true)
                .dynamicRendering(true)
                .maintenance4(true)
            f12 = VkPhysicalDeviceVulkan12Features.calloc()
                .sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                .pNext(f13!!.address())
                .hostQueryReset(true)
                .timelineSemaphore(true)
                .bufferDeviceAddress(true)
                .descriptorIndexing(true)
                .uniformBufferStandardLayout(true)
                .shaderSubgroupExtendedTypes(true)
                .vulkanMemoryModel(true)
                .vulkanMemoryModelDeviceScope(true)
            f11 = VkPhysicalDeviceVulkan11Features.calloc()
                .sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)
                .pNext(f12!!.address())
                .samplerYcbcrConversion(true)
                .storageBuffer16BitAccess(true)
            features2 = VkPhysicalDeviceFeatures2.calloc()
                .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(f11!!.address())
            features2!!.features()
                .shaderImageGatherExtended(true)
                .shaderStorageImageReadWithoutFormat(true)
                .shaderStorageImageWriteWithoutFormat(true)

            val qci = VkDeviceQueueCreateInfo.calloc(1, s)
            qci.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(s.floats(1.0f))

            val extNames = s.mallocPointer(DEVICE_EXTENSIONS.size)
            for (e in DEVICE_EXTENSIONS) extNames.put(s.ASCII(e))
            extNames.flip()

            val dci = VkDeviceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features2!!.address())
                .pQueueCreateInfos(qci)
                .ppEnabledExtensionNames(extNames)
            vkCheck(vkCreateDevice(physicalDevice, dci, null, pp), "vkCreateDevice")
            device = VkDevice(pp.get(0), physicalDevice, dci)

            vkGetDeviceQueue(device, queueFamily, 0, pp)
            queue = VkQueue(pp.get(0), device)

            val fci = VkFenceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            val pf = s.mallocLong(1)
            vkCheck(vkCreateFence(device, fci, null, pf), "vkCreateFence")
            drainFence = pf.get(0)
        }
    }

    private fun findMemoryType(typeBits: Int, wanted: Int): Int {
        stackPush().use { s ->
            val mp = VkPhysicalDeviceMemoryProperties.calloc(s)
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mp)
            for (i in 0 until mp.memoryTypeCount()) {
                if (typeBits and (1 shl i) != 0 &&
                    mp.memoryTypes(i).propertyFlags() and wanted == wanted
                ) return i
            }
        }
        throw IllegalStateException("no suitable memory type (bits=$typeBits wanted=$wanted)")
    }

    private fun allocateBuffer(b: Buffer, w: Int, h: Int) {
        stackPush().use { s ->
            // OPAQUE_FD declared at image creation: exportability is part of
            // the image's identity, not something bolted on at allocation.
            val ext = VkExternalMemoryImageCreateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            val ici = VkImageCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(ext.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(renderFormat)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                // OPTIMAL is fine across the fd: GL imports it with
                // GL_OPTIMAL_TILING_EXT, and the same driver owns both sides.
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(USAGE)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            ici.extent().width(w).height(h).depth(1)
            val pl = s.mallocLong(1)
            vkCheck(vkCreateImage(device, ici, null, pl), "vkCreateImage")
            b.image = pl.get(0)

            val mr = VkMemoryRequirements.calloc(s)
            vkGetImageMemoryRequirements(device, b.image, mr)
            // Dedicated allocation: required for OPAQUE_FD image export to be
            // importable by GL on NVIDIA (the GL side binds the whole memory
            // object to one texture), and it makes allocationSize exact.
            val dedicated = VkMemoryDedicatedAllocateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(b.image)
            val export = VkExportMemoryAllocateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .pNext(dedicated.address())
                .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            val ai = VkMemoryAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(export.address())
                .allocationSize(mr.size())
                .memoryTypeIndex(findMemoryType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            vkCheck(vkAllocateMemory(device, ai, null, pl), "vkAllocateMemory")
            b.memory = pl.get(0)
            b.allocationSize = mr.size()
            vkCheck(vkBindImageMemory(device, b.image, b.memory, 0), "vkBindImageMemory")

            val pFd = s.mallocInt(1)
            val mgfi = VkMemoryGetFdInfoKHR.calloc(s)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
                .memory(b.memory)
                .handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            vkCheck(vkGetMemoryFdKHR(device, mgfi, pFd), "vkGetMemoryFdKHR")
            b.memoryFd = pFd.get(0)

            val sExport = VkExportSemaphoreCreateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                .handleTypes(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT)
            val sci = VkSemaphoreCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                .pNext(sExport.address())
            vkCheck(vkCreateSemaphore(device, sci, null, pl), "vkCreateSemaphore")
            b.semaphore = pl.get(0)

            // Opaque-fd semaphores have reference transference: the fd names
            // the semaphore itself, so exporting once at creation covers every
            // future signal.
            val sgfi = VkSemaphoreGetFdInfoKHR.calloc(s)
                .sType(KHRExternalSemaphoreFd.VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR)
                .semaphore(b.semaphore)
                .handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT)
            vkCheck(vkGetSemaphoreFdKHR(device, sgfi, pFd), "vkGetSemaphoreFdKHR")
            b.semaphoreFd = pFd.get(0)

            // The reverse-direction twin: GL signals, this queue waits.
            // Same exportable-binary shape as the render-done semaphore.
            vkCheck(vkCreateSemaphore(device, sci, null, pl), "vkCreateSemaphore(glDone)")
            b.glDoneSemaphore = pl.get(0)
            vkCheck(
                vkGetSemaphoreFdKHR(device, sgfi.semaphore(b.glDoneSemaphore), pFd),
                "vkGetSemaphoreFdKHR(glDone)",
            )
            b.glDoneSemaphoreFd = pFd.get(0)

            b.width = w
            b.height = h
            b.format = renderFormat
            b.outLayout = VK_IMAGE_LAYOUT_UNDEFINED
            b.fdsOwnedByConsumer = false
            b.glDoneOwed = false
            b.generation = ++generationCounter
        }
    }

    private var generationCounter = 0

    private fun releaseBuffer(b: Buffer) {
        if (b.glDoneOwed) {
            // The consumer signaled glDone but no render waited it yet.
            // Drain: unsignals the semaphore and proves the GL-side signal
            // operation retired, so the destroy below cannot yank the payload
            // out from under it. (The signal was flushed before the flag was
            // set, so the bounded wait cannot time out in practice.)
            //
            // Non-fatal, unlike the dropped-frame drain: this also runs on the
            // teardown path, where the consumer's GL context may already be
            // gone. A signal that can then never arrive must not throw out of
            // destroyVulkan and leave the device -- and mpv's imported view of
            // it -- undestroyed; the vkDeviceWaitIdle/vkQueueWaitIdle around
            // this is what actually gates the destroys.
            try {
                drainSemaphore(b.glDoneSemaphore)
            } catch (t: Throwable) {
                println("[wayland-video] vk-pipeline: glDone drain failed: ${t.message}")
            }
            b.glDoneOwed = false
        }
        // The buffer's last render may still be executing on the GPU: when a
        // consumer took the frame it inherited the semaphore wait, so nothing
        // here proves mpv's submission targeting this image has retired.
        // Destroying a VkImage or freeing VkDeviceMemory under in-flight work
        // is undefined behaviour that surfaces later as driver-side memory
        // corruption (the hs_err SIGSEGVs in libnvidia-eglcore). mpv submits
        // on this same queue, so one idle-wait covers its renders too. Rare
        // path: reallocation (resize) and teardown only.
        if (b.image != VK_NULL_HANDLE || b.memory != VK_NULL_HANDLE) {
            vkQueueWaitIdle(queue)
        }
        if (!b.fdsOwnedByConsumer) {
            if (b.memoryFd >= 0) Posix.close(b.memoryFd)
            if (b.semaphoreFd >= 0) Posix.close(b.semaphoreFd)
            if (b.glDoneSemaphoreFd >= 0) Posix.close(b.glDoneSemaphoreFd)
        }
        b.memoryFd = -1
        b.semaphoreFd = -1
        b.glDoneSemaphoreFd = -1
        b.fdsOwnedByConsumer = false
        if (b.semaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, b.semaphore, null); b.semaphore = VK_NULL_HANDLE
        }
        if (b.glDoneSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, b.glDoneSemaphore, null); b.glDoneSemaphore = VK_NULL_HANDLE
        }
        if (b.image != VK_NULL_HANDLE) {
            vkDestroyImage(device, b.image, null); b.image = VK_NULL_HANDLE
        }
        if (b.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device, b.memory, null); b.memory = VK_NULL_HANDLE
        }
        b.width = 0
        b.height = 0
        b.allocationSize = 0
        b.signalPending = false
    }

    /**
     * Unsignal a binary semaphore whose frame was dropped: an empty submission
     * that waits on it, fenced so this thread knows when it retired. Runs on
     * the render thread, so it cannot race mpv's own submissions.
     */
    private fun drainSemaphore(sem: Long) {
        val t0 = System.nanoTime()
        drains++
        stackPush().use { s ->
            val si = VkSubmitInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(s.longs(sem))
                .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
            withQueue { vkCheck(vkQueueSubmit(queue, si, drainFence), "vkQueueSubmit(drain)") }
            // Bounded: if the signal never comes (a failed render that never
            // submitted), an infinite wait would wedge the whole pipeline.
            val r = vkWaitForFences(device, drainFence, true, 1_000_000_000L)
            check(r == VK_SUCCESS) { "semaphore drain did not retire (VkResult $r)" }
            vkResetFences(device, drainFence)
            drainNanos += System.nanoTime() - t0
        }
    }

    /**
     * Order mpv's next write into a buffer after the consumer's finished
     * reads: an empty submission that waits the buffer's glDone semaphore.
     * No fence and no CPU wait -- a semaphore wait's second synchronization
     * scope covers everything later in submission order on this queue, and
     * mpv submits to this same queue (single-queue device, imported by the
     * render context), so every subsequent render is ordered after it.
     * Runs on the render thread, so it cannot race mpv's own submissions.
     */
    private fun waitGlDone(sem: Long) {
        stackPush().use { s ->
            val si = VkSubmitInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(s.longs(sem))
                .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
            withQueue {
                vkCheck(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "vkQueueSubmit(glDone wait)")
            }
        }
    }

    private fun destroyVulkan() {
        if (::device.isInitialized) {
            vkDeviceWaitIdle(device)
            for (b in buffers) releaseBuffer(b)
            if (drainFence != VK_NULL_HANDLE) {
                vkDestroyFence(device, drainFence, null); drainFence = VK_NULL_HANDLE
            }
            vkDestroyDevice(device, null)
        }
        if (::instance.isInitialized) vkDestroyInstance(instance, null)
        features2?.free(); features2 = null
        f11?.free(); f11 = null
        f12?.free(); f12 = null
        f13?.free(); f13 = null
    }

    /** Exported fds are raw kernel fds; only libc can close them. */
    /** Also used by ChromeLayer, to close a dmabuf fd a failed import left over. */
    internal object Posix {
        private val closeHandle = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )

        fun close(fd: Int) {
            closeHandle.invokeExact(fd) as Int
        }

        private val exitHandle = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("_exit").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
        )

        /**
         * End the process now, without atexit handlers or static destructors.
         *
         * Runtime.halt() still goes through exit(), and the NVIDIA driver's own
         * cleanup runs there -- faulting inside libnvidia-eglcore once the
         * contexts it registered are gone, which is the crash dump on close.
         * Nothing of ours is left to run: the teardown has finished and mpv,
         * the only component with state worth flushing, stopped first.
         */
        fun exitNow(code: Int): Nothing {
            exitHandle.invokeExact(code)
            error("_exit returned")
        }
    }
}
