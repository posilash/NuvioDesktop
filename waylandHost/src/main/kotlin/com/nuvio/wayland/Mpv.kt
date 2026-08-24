package com.nuvio.wayland

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Minimal libmpv render-API binding over the Foreign Function & Memory API.
 *
 * Deliberately not JNI: this needs no native build step, and the render API is
 * a small enough surface that binding it directly is less code than a wrapper
 * would be.
 *
 * Only what the Wayland host needs is bound. The interesting part is
 * [MPV_RENDER_API_TYPE_OPENGL_NEXT]: it takes exactly the same parameters as
 * the long-standing "opengl" type but renders with libplacebo -- the same
 * renderer vo=gpu-next uses -- rather than the legacy gl_video one. For a
 * client that is a one-string change.
 */
class Mpv private constructor(private val handle: MemorySegment, private val arena: Arena) {

    companion object {
        /** Blocking-paced render + swap feedback; default is free-run. */
        val pacedMode: Boolean =
            System.getProperty("nuvio.wayland.paced")?.toBoolean() == true

        const val MPV_RENDER_PARAM_INVALID = 0
        const val MPV_RENDER_PARAM_API_TYPE = 1
        const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
        const val MPV_RENDER_PARAM_OPENGL_FBO = 3
        const val MPV_RENDER_PARAM_FLIP_Y = 4
        const val MPV_RENDER_PARAM_ADVANCED_CONTROL = 10
        const val MPV_RENDER_PARAM_NEXT_FRAME_INFO = 11
        const val MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
        const val MPV_RENDER_PARAM_VULKAN_INIT_PARAMS = 21
        const val MPV_RENDER_PARAM_VULKAN_FBO = 22

        const val MPV_RENDER_UPDATE_FRAME = 1L

        const val MPV_RENDER_FRAME_INFO_PRESENT = 1L shl 0

        const val MPV_RENDER_API_TYPE_OPENGL = "opengl"
        const val MPV_RENDER_API_TYPE_OPENGL_NEXT = "opengl-next"
        const val MPV_RENDER_API_TYPE_VULKAN = "vulkan"

        // struct mpv_render_param { int type; void *data; }  -- 4 + 4 pad + 8
        private val RENDER_PARAM: MemoryLayout = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("data"),
        )

        // struct mpv_render_frame_info { uint64_t flags; int64_t target_time; }
        private val RENDER_FRAME_INFO: MemoryLayout = MemoryLayout.structLayout(
            java.lang.foreign.ValueLayout.JAVA_LONG.withName("flags"),
            java.lang.foreign.ValueLayout.JAVA_LONG.withName("target_time"),
        )

        // struct mpv_opengl_fbo { int fbo, w, h, internal_format; }
        private val OPENGL_FBO: MemoryLayout = MemoryLayout.structLayout(
            JAVA_INT.withName("fbo"),
            JAVA_INT.withName("w"),
            JAVA_INT.withName("h"),
            JAVA_INT.withName("internal_format"),
        )

        // struct mpv_vulkan_init_params (fork mpv/render_vk.h) -- 104 bytes.
        // Field offsets verified with _Static_assert(offsetof(...)) against the
        // real header; the numeric offsets in createRenderContextVulkan() match
        // this layout, not vice versa.
        private val VULKAN_INIT_PARAMS: MemoryLayout = MemoryLayout.structLayout(
            ADDRESS.withName("instance"),          // @0   VkInstance
            ADDRESS.withName("phys_device"),       // @8   VkPhysicalDevice
            ADDRESS.withName("device"),            // @16  VkDevice
            ADDRESS.withName("get_proc_addr"),     // @24  PFN_vkGetInstanceProcAddr
            ADDRESS.withName("extensions"),        // @32  const char *const *
            JAVA_INT.withName("num_extensions"),   // @40
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("features"),          // @48  const VkPhysicalDeviceFeatures2 *
            JAVA_INT.withName("queue_graphics_index"), // @56  mpv_vulkan_queue x3
            JAVA_INT.withName("queue_graphics_count"),
            JAVA_INT.withName("queue_compute_index"),
            JAVA_INT.withName("queue_compute_count"),
            JAVA_INT.withName("queue_transfer_index"),
            JAVA_INT.withName("queue_transfer_count"),
            ADDRESS.withName("lock_queue"),        // @80
            ADDRESS.withName("unlock_queue"),      // @88
            ADDRESS.withName("queue_ctx"),         // @96
        )

        // struct mpv_vulkan_fbo (fork mpv/render_vk.h) -- 64 bytes, no padding:
        // the seven leading 4-byte fields end at 32, realigning the semaphores.
        private val VULKAN_FBO: MemoryLayout = MemoryLayout.structLayout(
            JAVA_LONG.withName("image"),            // @0   VkImage (non-dispatchable, u64)
            JAVA_INT.withName("format"),            // @8   VkFormat
            JAVA_INT.withName("w"),                 // @12
            JAVA_INT.withName("h"),                 // @16
            JAVA_INT.withName("usage"),             // @20  VkImageUsageFlags
            JAVA_INT.withName("layout"),            // @24  VkImageLayout on entry
            JAVA_INT.withName("out_layout"),        // @28  in/out
            JAVA_LONG.withName("signal_semaphore"), // @32  VkSemaphore, required
            JAVA_LONG.withName("signal_value"),     // @40  0 = binary semaphore
            JAVA_LONG.withName("wait_semaphore"),   // @48  optional
            JAVA_LONG.withName("wait_value"),       // @56
        )

        private val linker: Linker = Linker.nativeLinker()
        private lateinit var lookup: SymbolLookup

        private fun fn(name: String, desc: FunctionDescriptor) =
            linker.downcallHandle(
                lookup.find(name).orElseThrow { UnsatisfiedLinkError("libmpv: $name") },
                desc,
            )

        private val mpvCreate by lazy { fn("mpv_create", FunctionDescriptor.of(ADDRESS)) }
        private val mpvInitialize by lazy {
            fn("mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        }
        private val mpvSetOptionString by lazy {
            fn("mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        }
        private val mpvCommand by lazy {
            fn("mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }
        private val mpvTerminateDestroy by lazy {
            fn("mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS))
        }
        private val mpvRenderContextCreate by lazy {
            fn("mpv_render_context_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        }
        private val mpvRenderContextRender by lazy {
            fn("mpv_render_context_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }
        private val mpvRenderContextUpdate by lazy {
            fn("mpv_render_context_update", FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_LONG, ADDRESS))
        }
        private val mpvLoadConfigFile by lazy {
            fn("mpv_load_config_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }
        private val mpvRenderContextSetUpdateCallback by lazy {
            fn("mpv_render_context_set_update_callback",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
        }
        // mpv_render_context_get_info takes its param struct by value.
        private val mpvRenderContextGetInfo by lazy {
            fn("mpv_render_context_get_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, RENDER_PARAM))
        }
        private val mpvGetTimeNs by lazy {
            fn("mpv_get_time_ns",
                FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_LONG, ADDRESS))
        }
        private val mpvRenderContextFree by lazy {
            fn("mpv_render_context_free", FunctionDescriptor.ofVoid(ADDRESS))
        }
        private val mpvSetPropertyString by lazy {
            fn("mpv_set_property_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        }
        private val mpvGetPropertyString by lazy {
            fn("mpv_get_property_string", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        }
        private val mpvFree by lazy {
            fn("mpv_free", FunctionDescriptor.ofVoid(ADDRESS))
        }
        private val mpvRequestLogMessages by lazy {
            fn("mpv_request_log_messages", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }
        private val mpvRenderContextReportSwap by lazy {
            fn("mpv_render_context_report_swap", FunctionDescriptor.ofVoid(ADDRESS))
        }
        private val mpvObserveProperty by lazy {
            fn("mpv_observe_property", FunctionDescriptor.of(JAVA_INT, ADDRESS,
                JAVA_LONG, ADDRESS, JAVA_INT))
        }
        private val mpvWaitEvent by lazy {
            fn("mpv_wait_event", FunctionDescriptor.of(ADDRESS, ADDRESS,
                java.lang.foreign.ValueLayout.JAVA_DOUBLE))
        }

        /** Load libmpv. Pass an explicit path to use a build other than the system one. */
        fun load(path: String?): Boolean = runCatching {
            val arena = Arena.global()
            lookup = if (path != null) {
                SymbolLookup.libraryLookup(java.nio.file.Path.of(path), arena)
            } else {
                SymbolLookup.libraryLookup("mpv", arena)
            }
            true
        }.getOrElse { false }

        // glibc LC_NUMERIC. mpv_create() returns NULL unless LC_NUMERIC is "C",
        // and the JVM sets a locale from the environment, so this has to be
        // forced first. (Nuvio's C++ bridge does the same via <clocale>.)
        private const val LC_NUMERIC = 1

        private fun forceCNumericLocale() {
            runCatching {
                val libc = Linker.nativeLinker().defaultLookup()
                val setlocale = linker.downcallHandle(
                    libc.find("setlocale").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS),
                )
                Arena.ofConfined().use { a ->
                    setlocale.invokeExact(LC_NUMERIC, a.allocateFrom("C")) as MemorySegment
                }
            }
        }

        fun create(): Mpv {
            forceCNumericLocale()
            val arena = Arena.ofShared()
            val h = mpvCreate.invokeExact() as MemorySegment
            check(!h.equals(MemorySegment.NULL)) {
                "mpv_create failed (LC_NUMERIC must be \"C\")"
            }
            return Mpv(h, arena)
        }
    }

    private var renderCtx: MemorySegment = MemorySegment.NULL
    private var procAddressStub: MemorySegment = MemorySegment.NULL

    @Volatile
    private var shuttingDown = false

    fun setOption(name: String, value: String) {
        val r = mpvSetOptionString.invokeExact(
            handle, arena.allocateFrom(name), arena.allocateFrom(value),
        ) as Int
        check(r >= 0) { "mpv_set_option_string($name) -> $r" }
    }

    /**
     * Parse a config file right now, at a caller-chosen point in option
     * ordering -- unlike config=yes, whose file is parsed at initialize() and
     * silently overwrites everything set before it. Options set after this
     * call win over the file: the deterministic layering an embedding host
     * needs (load the user's config, then assert invariants).
     */
    fun loadConfigFile(path: String): Boolean {
        return Arena.ofConfined().use { a ->
            (mpvLoadConfigFile.invokeExact(handle, a.allocateFrom(path)) as Int) >= 0
        }
    }

    fun initialize() {
        val r = mpvInitialize.invokeExact(handle) as Int
        check(r >= 0) { "mpv_initialize -> $r" }
    }

    fun command(vararg args: String) {
        if (shuttingDown) return
        val arr = arena.allocate(ADDRESS, (args.size + 1).toLong())
        args.forEachIndexed { i, a -> arr.setAtIndex(ADDRESS, i.toLong(), arena.allocateFrom(a)) }
        arr.setAtIndex(ADDRESS, args.size.toLong(), MemorySegment.NULL)
        mpvCommand.invokeExact(handle, arr) as Int
    }

    /**
     * Create the render context. [getProcAddress] resolves GL entry points; the
     * host supplies it because mpv must use the same loader the window system
     * does -- here GLFW's, so the EGL context GLFW created is the one mpv sees.
     */
    fun createRenderContext(apiType: String, getProcAddress: (String) -> Long) {
        val upcallType = MethodType.methodType(
            MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
        )
        val target = MethodHandles.lookup().findStatic(
            ProcAddressBridge::class.java, "resolve", upcallType,
        )
        ProcAddressBridge.resolver = getProcAddress
        procAddressStub = linker.upcallStub(
            target,
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            arena,
        )

        // struct mpv_opengl_init_params { get_proc_address; ctx; }
        val glInit = arena.allocate(ADDRESS, 2)
        glInit.setAtIndex(ADDRESS, 0, procAddressStub)
        glInit.setAtIndex(ADDRESS, 1, MemorySegment.NULL)

        // Stremio's model, adopted deliberately: WITHOUT advanced control
        // mpv's core free-runs on its own clock and render() hands over the
        // current frame non-blocking. There is no timing contract for the
        // host to violate: a late present repeats a frame silently instead
        // of cascading into counted drops. The paced mode (blocking render +
        // report_swap) measures golden when nothing delays presents, and is
        // kept behind -Dnuvio.wayland.paced=true for A/B.
        val advanced = arena.allocateFrom(JAVA_INT, if (pacedMode) 1 else 0)

        val params = arena.allocate(RENDER_PARAM, 4)
        fun put(i: Int, type: Int, data: MemorySegment) {
            val off = i.toLong() * RENDER_PARAM.byteSize()
            params.set(JAVA_INT, off, type)
            params.set(ADDRESS, off + 8, data)
        }
        put(0, MPV_RENDER_PARAM_API_TYPE, arena.allocateFrom(apiType))
        put(1, MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, glInit)
        put(2, MPV_RENDER_PARAM_ADVANCED_CONTROL, advanced)
        put(3, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)

        val out = arena.allocate(ADDRESS)
        val r = mpvRenderContextCreate.invokeExact(out, handle, params) as Int
        check(r >= 0) { "mpv_render_context_create($apiType) -> $r" }
        renderCtx = out.get(ADDRESS, 0)
    }

    /**
     * Create a "vulkan" render context against a device the host owns.
     *
     * Fork-only API (mpv/render_vk.h, branch vk-hwdec): upstream libmpv's
     * render API supports only the GL and sw backends. mpv imports the given
     * device via libplacebo instead of creating its own, so it renders
     * straight into VkImages the host allocated -- the piece that makes a
     * VK->GL memory-object bridge (or a future all-Vulkan window) possible.
     *
     * All handles are raw Vulkan handles as longs; [getInstanceProcAddr] is
     * the *address of* vkGetInstanceProcAddr from whichever loader created
     * [instance] -- mpv cannot assume it links against the same one.
     * [features] points to the VkPhysicalDeviceFeatures2 chain the device was
     * created with (0 = none), and [extensions] lists the device extensions
     * enabled at creation; libplacebo trusts both, and lying produces
     * validation errors rather than clean failures.
     *
     * The device must have been created with VK_KHR_external_memory_fd and
     * VK_KHR_external_semaphore_fd enabled *and* those names listed in
     * [extensions]: the fork's zero-copy nvdec interop shares decoded frames
     * by exporting Vulkan memory and semaphores as fds, and libplacebo only
     * uses what the client reports -- omit either half and hwdec silently
     * degrades to nvdec-copy, round-tripping every frame through system
     * memory.
     */
    fun createRenderContextVulkan(
        instance: Long,
        physDevice: Long,
        device: Long,
        getInstanceProcAddr: Long,
        features: Long,
        extensions: List<String>,
        queueFamily: Int,
        queueCount: Int = 1,
    ) {
        val init = arena.allocate(VULKAN_INIT_PARAMS)
        init.set(ADDRESS, 0, MemorySegment.ofAddress(instance))
        init.set(ADDRESS, 8, MemorySegment.ofAddress(physDevice))
        init.set(ADDRESS, 16, MemorySegment.ofAddress(device))
        init.set(ADDRESS, 24, MemorySegment.ofAddress(getInstanceProcAddr))
        if (extensions.isEmpty()) {
            init.set(ADDRESS, 32, MemorySegment.NULL)
        } else {
            val arr = arena.allocate(ADDRESS, extensions.size.toLong())
            extensions.forEachIndexed { i, e ->
                arr.setAtIndex(ADDRESS, i.toLong(), arena.allocateFrom(e))
            }
            init.set(ADDRESS, 32, arr)
        }
        init.set(JAVA_INT, 40, extensions.size)
        init.set(ADDRESS, 48, MemorySegment.ofAddress(features))
        // One family for graphics, compute and transfer: the common case, and
        // the one this host has (NVIDIA family 0 is all three).
        for (off in longArrayOf(56, 64, 72)) {
            init.set(JAVA_INT, off, queueFamily)
            init.set(JAVA_INT, off + 4, queueCount)
        }
        // lock_queue/unlock_queue/queue_ctx stay NULL: the video thread is the
        // only submitter to mpv's queues, so no cross-thread queue sharing.

        val advanced = arena.allocateFrom(JAVA_INT, if (pacedMode) 1 else 0)
        val params = arena.allocate(RENDER_PARAM, 4)
        fun put(i: Int, type: Int, data: MemorySegment) {
            val off = i.toLong() * RENDER_PARAM.byteSize()
            params.set(JAVA_INT, off, type)
            params.set(ADDRESS, off + 8, data)
        }
        put(0, MPV_RENDER_PARAM_API_TYPE, arena.allocateFrom(MPV_RENDER_API_TYPE_VULKAN))
        put(1, MPV_RENDER_PARAM_VULKAN_INIT_PARAMS, init)
        put(2, MPV_RENDER_PARAM_ADVANCED_CONTROL, advanced)
        put(3, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)

        val out = arena.allocate(ADDRESS)
        val r = mpvRenderContextCreate.invokeExact(out, handle, params) as Int
        check(r >= 0) { "mpv_render_context_create(vulkan) -> $r" }
        renderCtx = out.get(ADDRESS, 0)
    }

    /** One target VkImage for [renderVulkan]. All handles raw, as longs. */
    class VulkanFrame(
        val image: Long,
        val format: Int,
        val w: Int,
        val h: Int,
        val usage: Int,
        /** Layout the image is in on entry; 0 = VK_IMAGE_LAYOUT_UNDEFINED. */
        val layout: Int = 0,
        /** Layout mpv should leave it in; 0 lets mpv choose (reported back). */
        val outLayout: Int = 0,
        /** Required: mpv signals this when rendering finished. */
        val signalSemaphore: Long,
        val signalValue: Long = 0,
        /** Optional: mpv waits on this before touching the image. */
        val waitSemaphore: Long = 0,
        val waitValue: Long = 0,
    )

    /**
     * Render one frame into [frame]'s VkImage. Same pacing contract as
     * [render]: blocks until presentation time, so video-thread only.
     *
     * Rendering is asynchronous even after this returns -- the image is only
     * safe to sample once [VulkanFrame.signalSemaphore] has signalled. Returns
     * the mpv error code and the layout mpv left the image in (out_layout is
     * an in/out field; mpv writes back its choice when 0 was passed).
     */
    fun renderVulkan(frame: VulkanFrame): Pair<Int, Int> {
        if (renderCtx.equals(MemorySegment.NULL)) return -1 to 0
        Arena.ofConfined().use { a ->
            val fbo = a.allocate(VULKAN_FBO)
            fbo.set(JAVA_LONG, 0, frame.image)
            fbo.set(JAVA_INT, 8, frame.format)
            fbo.set(JAVA_INT, 12, frame.w)
            fbo.set(JAVA_INT, 16, frame.h)
            fbo.set(JAVA_INT, 20, frame.usage)
            fbo.set(JAVA_INT, 24, frame.layout)
            fbo.set(JAVA_INT, 28, frame.outLayout)
            fbo.set(JAVA_LONG, 32, frame.signalSemaphore)
            fbo.set(JAVA_LONG, 40, frame.signalValue)
            fbo.set(JAVA_LONG, 48, frame.waitSemaphore)
            fbo.set(JAVA_LONG, 56, frame.waitValue)

            val params = a.allocate(RENDER_PARAM, 2)
            params.set(JAVA_INT, 0, MPV_RENDER_PARAM_VULKAN_FBO)
            params.set(ADDRESS, 8, fbo)
            val off = RENDER_PARAM.byteSize()
            params.set(JAVA_INT, off, MPV_RENDER_PARAM_INVALID)
            params.set(ADDRESS, off + 8, MemorySegment.NULL)

            val r = mpvRenderContextRender.invokeExact(renderCtx, params) as Int
            return r to fbo.get(JAVA_INT, 28)
        }
    }

    /** Raw mpv_render_context_update() flags, for diagnostics. */
    @Volatile
    var lastUpdateFlags: Long = -1
        private set

    fun hasNewFrame(): Boolean {
        if (renderCtx.equals(MemorySegment.NULL)) return false
        val flags = mpvRenderContextUpdate.invokeExact(renderCtx) as Long
        lastUpdateFlags = flags
        return (flags and MPV_RENDER_UPDATE_FRAME) != 0L
    }

    /** mpv's internal clock, in the same base as [FrameInfo.targetTimeNs]. */
    fun timeNs(): Long = mpvGetTimeNs.invokeExact(handle) as Long

    /**
     * When the next frame is due, so the caller can do the pacing itself.
     *
     * [targetTimeNs] is nanoseconds on mpv's own clock, matching [timeNs].
     * render.h still documents it as `mpv_get_time_us()` units, but
     * `vo_libmpv.c` assigns it straight from `vo_frame.pts`, which `vo.h`
     * defines in `mp_time_ns()` units -- the doc predates mpv's move to
     * nanoseconds. Reading it as microseconds puts every frame a thousandfold
     * into the future, so nothing ever looks due and the video never draws.
     */
    data class FrameInfo(val flags: Long, val targetTimeNs: Long) {
        val isPresent: Boolean get() = (flags and MPV_RENDER_FRAME_INFO_PRESENT) != 0L
    }

    /**
     * Ask mpv about the frame it would draw next.
     *
     * This is what makes it safe to turn off `BLOCK_FOR_TARGET_TIME`: mpv
     * normally does the frame pacing by *sleeping inside* render(), which is
     * fine on a dedicated render thread and ruinous on one that also draws the
     * UI. Reading the deadline instead lets the caller keep the display's
     * cadence and present each frame when it is actually due.
     */
    fun nextFrameInfo(): FrameInfo? {
        if (renderCtx.equals(MemorySegment.NULL)) return null
        Arena.ofConfined().use { a ->
            val info = a.allocate(RENDER_FRAME_INFO)
            val param = a.allocate(RENDER_PARAM)
            param.set(JAVA_INT, 0, MPV_RENDER_PARAM_NEXT_FRAME_INFO)
            param.set(ADDRESS, 8, info)
            val r = mpvRenderContextGetInfo.invokeExact(renderCtx, param) as Int
            if (r < 0) return null
            return FrameInfo(info.get(JAVA_LONG, 0), info.get(JAVA_LONG, 8))
        }
    }

    /**
     * Render one frame into [fbo] at [w] x [h].
     *
     * Blocks until the frame's presentation time -- mpv's own display sync.
     * That is precisely what a dedicated video thread wants, and precisely
     * what must never run on a thread that also draws UI: an earlier revision
     * called this on the Compose thread and pinned the entire application to
     * the video's frame rate. The pacing belongs to mpv; the threading
     * belongs to the caller.
     */
    fun render(fbo: Int, w: Int, h: Int): Int {
        if (renderCtx.equals(MemorySegment.NULL)) return -1
        Arena.ofConfined().use { frame ->
            val target = frame.allocate(OPENGL_FBO)
            target.set(JAVA_INT, 0, fbo)
            target.set(JAVA_INT, 4, w)
            target.set(JAVA_INT, 8, h)
            target.set(JAVA_INT, 12, 0) // internal_format: 0 = let mpv choose
            val flip = frame.allocateFrom(JAVA_INT, 0)

            val params = frame.allocate(RENDER_PARAM, 3)
            fun put(i: Int, type: Int, data: MemorySegment) {
                val off = i.toLong() * RENDER_PARAM.byteSize()
                params.set(JAVA_INT, off, type)
                params.set(ADDRESS, off + 8, data)
            }
            put(0, MPV_RENDER_PARAM_OPENGL_FBO, target)
            put(1, MPV_RENDER_PARAM_FLIP_Y, flip)
            put(2, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)

            return mpvRenderContextRender.invokeExact(renderCtx, params) as Int
        }
    }

    /**
     * Install the render-context update callback.
     *
     * mpv invokes it from an internal thread whenever a new frame (or other
     * update) is available; [callback] must only signal -- never call back
     * into mpv, and never touch GL.
     */
    fun setUpdateCallback(callback: Runnable) {
        check(!renderCtx.equals(MemorySegment.NULL)) { "render context not created" }
        UpdateCallbackBridge.callback = callback
        val target = MethodHandles.lookup().findStatic(
            UpdateCallbackBridge::class.java, "invoke",
            MethodType.methodType(Void.TYPE, MemorySegment::class.java),
        )
        val stub = linker.upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS), arena)
        mpvRenderContextSetUpdateCallback.invokeExact(renderCtx, stub, MemorySegment.NULL)
    }

    /**
     * Free the render context. Must be called from the thread that owns the
     * GL context it was created against, before [close].
     */
    fun freeRenderContext() {
        if (renderCtx.equals(MemorySegment.NULL)) return
        mpvRenderContextFree.invokeExact(renderCtx)
        renderCtx = MemorySegment.NULL
    }

    /** Ask mpv to deliver its own log at [level] (e.g. "v", "debug"). */
    fun requestLogMessages(level: String) {
        Arena.ofConfined().use { a ->
            mpvRequestLogMessages.invokeExact(handle, a.allocateFrom(level)) as Int
        }
    }

    /**
     * Tell mpv a swap just happened. With ADVANCED_CONTROL this is the vsync
     * feedback a real VO gets: mpv aligns frame target times to the display's
     * actual cadence instead of freewheeling on its own clock. Callable from
     * any thread per render.h.
     */
    fun reportSwap() {
        if (!pacedMode) return
        if (renderCtx.equals(MemorySegment.NULL) || shuttingDown) return
        mpvRenderContextReportSwap.invokeExact(renderCtx)
    }

    /** Subscribe to string-format change events for [name]. */
    fun observeProperty(name: String) {
        Arena.ofConfined().use { a ->
            // MPV_FORMAT_STRING = 1: every observed value arrives as text,
            // parsed at read time -- one code path, no per-type marshalling.
            mpvObserveProperty.invokeExact(handle, 0L, a.allocateFrom(name), 1) as Int
        }
    }

    /**
     * The event loop, on its own thread -- the only consumer of mpv's event
     * queue. Observed property changes land in [properties]; playback state
     * reads become cache hits instead of core-lock round-trips. That is the
     * design point: property polling -- from any thread -- contends with the
     * core while the video thread paces inside render(), and the measured
     * result was 66ms present gaps at the poll rate. The core pushes; nobody
     * polls.
     */
    val properties = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val shutdownLatch = java.util.concurrent.CountDownLatch(1)
    private var eventThread: Thread? = null

    fun startEventLoop(logSink: ((String) -> Unit)?) {
        check(eventThread == null)
        eventThread = Thread({
            while (true) {
                val ev = mpvWaitEvent.invokeExact(handle, 0.5) as MemorySegment
                if (ev.equals(MemorySegment.NULL)) continue
                val e = ev.reinterpret(64)
                when (e.get(JAVA_INT, 0)) {
                    0 -> {} // MPV_EVENT_NONE: timeout tick
                    1 -> {  // MPV_EVENT_SHUTDOWN: core is gone; stop consuming
                        shutdownLatch.countDown()
                        return@Thread
                    }
                    2 -> if (logSink != null) { // MPV_EVENT_LOG_MESSAGE
                        val data = e.get(ADDRESS, 16)
                        if (!data.equals(MemorySegment.NULL)) {
                            val d = data.reinterpret(32)
                            val prefix = d.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
                            val text = d.get(ADDRESS, 16).reinterpret(Long.MAX_VALUE).getString(0)
                            logSink("[mpv/$prefix] " + text.trimEnd())
                        }
                    }
                    22 -> { // MPV_EVENT_PROPERTY_CHANGE
                        // struct mpv_event_property { name; format:int; data; }
                        val data = e.get(ADDRESS, 16)
                        if (!data.equals(MemorySegment.NULL)) {
                            val d = data.reinterpret(32)
                            val name = d.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
                            val format = d.get(JAVA_INT, 8)
                            if (format == 1) { // MPV_FORMAT_STRING: char**
                                val strPtr = d.get(ADDRESS, 16)
                                if (!strPtr.equals(MemorySegment.NULL)) {
                                    val cstr = strPtr.reinterpret(8).get(ADDRESS, 0)
                                    if (!cstr.equals(MemorySegment.NULL)) {
                                        properties[name] =
                                            cstr.reinterpret(Long.MAX_VALUE).getString(0)
                                    }
                                }
                            } else {
                                // MPV_FORMAT_NONE: property became unavailable
                                properties.remove(name)
                            }
                        }
                    }
                }
            }
        }, "mpv-events").apply { isDaemon = true; start() }
    }

    fun cachedString(name: String): String? = properties[name]

    fun cachedDouble(name: String): Double? = properties[name]?.toDoubleOrNull()
    fun cachedBoolean(name: String): Boolean? =
        properties[name]?.let { it == "yes" || it == "true" }

    /**
     * Drain pending events, forwarding mpv's log lines to [sink].
     *
     * struct mpv_event { event_id:int, error:int, reply_userdata:uint64, data:ptr }
     * struct mpv_event_log_message { prefix:ptr, level:ptr, text:ptr, log_level:int }
     */
    fun pumpEvents(sink: (String) -> Unit) {
        while (true) {
            val ev = mpvWaitEvent.invokeExact(handle, 0.0) as MemorySegment
            if (ev.equals(MemorySegment.NULL)) return
            val e = ev.reinterpret(64)
            val id = e.get(JAVA_INT, 0)
            if (id == 0) return // MPV_EVENT_NONE
            if (id == 2) {      // MPV_EVENT_LOG_MESSAGE
                val data = e.get(ADDRESS, 16)
                if (!data.equals(MemorySegment.NULL)) {
                    val d = data.reinterpret(32)
                    val prefix = d.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
                    val text = d.get(ADDRESS, 16).reinterpret(Long.MAX_VALUE).getString(0)
                    sink("[mpv/$prefix] " + text.trimEnd())
                }
            }
        }
    }

    fun setProperty(name: String, value: String) {
        if (shuttingDown) return
        Arena.ofConfined().use { a ->
            mpvSetPropertyString.invokeExact(
                handle, a.allocateFrom(name), a.allocateFrom(value),
            ) as Int
        }
    }

    /** Null when the property is unavailable, which mpv reports routinely. */
    fun getProperty(name: String): String? {
        if (shuttingDown) return null
        return Arena.ofConfined().use { a ->
        val p = mpvGetPropertyString.invokeExact(handle, a.allocateFrom(name)) as MemorySegment
        if (p.equals(MemorySegment.NULL)) return null
        val s = p.reinterpret(Long.MAX_VALUE).getString(0)
        mpvFree.invokeExact(p)
        s
    }
    }

    fun getDouble(name: String): Double? = getProperty(name)?.toDoubleOrNull()
    fun getBoolean(name: String): Boolean? = getProperty(name)?.let { it == "yes" || it == "true" }

    /**
     * Ask the core to quit and wait until it reports MPV_EVENT_SHUTDOWN.
     *
     * This is the render API's documented teardown order: freeing the render
     * context while the core's VO still exists races its dispatch queues --
     * observed as `queue_dtor: Assertion !queue->lock_requests` aborting the
     * whole process when the window was closed after playback. After the
     * shutdown event, the core guarantees the VO is gone and
     * mpv_render_context_free is safe.
     */
    fun quitAndAwaitShutdown(timeoutSeconds: Double = 5.0, onWait: () -> Unit = {}) {
        command("quit")
        // From here on the core is winding down and, per client.h, the only
        // legal call left is mpv_terminate_destroy. Anything still holding a
        // reference -- a composable's onDispose, a straggling poll -- gets a
        // no-op instead of an abort or a use of a freed arena.
        shuttingDown = true
        // The event thread is the queue's only consumer; it trips the latch
        // on MPV_EVENT_SHUTDOWN and exits. [onWait] keeps the host's event
        // loop breathing meanwhile: a teardown that stops answering the
        // compositor's pings gets the window flagged unresponsive and the
        // process force-killed -- the "crash on close" that was really
        // Hyprland's ANR killer.
        val deadline = System.nanoTime() + (timeoutSeconds * 1e9).toLong()
        while (System.nanoTime() < deadline) {
            onWait()
            if (shutdownLatch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) break
        }
        eventThread?.join(1000)
    }

    fun close() {
        // The render context belongs to the video thread's GL context; freeing
        // it from here would touch GL from the wrong thread. freeRenderContext()
        // runs on that thread during its shutdown, before this is called.
        check(renderCtx.equals(MemorySegment.NULL)) {
            "freeRenderContext() must run (on the video thread) before close()"
        }
        mpvTerminateDestroy.invokeExact(handle)
        arena.close()
    }
}

/** Upcall target for the render-context update callback. */
internal object UpdateCallbackBridge {
    @JvmStatic
    @Volatile
    var callback: Runnable? = null

    @JvmStatic
    fun invoke(ctx: MemorySegment) {
        callback?.run()
    }
}

/** Upcall target for mpv's GL loader. FFM upcalls need a static method. */
internal object ProcAddressBridge {
    @JvmStatic
    var resolver: ((String) -> Long)? = null

    @JvmStatic
    fun resolve(ctx: MemorySegment, name: MemorySegment): MemorySegment {
        val n = name.reinterpret(Long.MAX_VALUE).getString(0)
        val addr = resolver?.invoke(n) ?: 0L
        return MemorySegment.ofAddress(addr)
    }
}
