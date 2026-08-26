package com.nuvio.wayland.wpe

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Upstream's desktop player chrome (player-ui/controls.html) as WPE WebKit,
 * hosted the way Stremio hosts its web UI: composited INTO the same scene
 * and present stream as the video, one window surface, one swapchain. (A
 * wl_subsurface variant -- a second commit stream -- made the chrome and the
 * video visibly fight whenever both were animating.)
 *
 * Two export paths, chosen by `-Dnuvio.wayland.chromeGpu` (default true):
 *
 * - GPU (`chromeGpu=true`): WebKit renders on the GPU and fdo hands over each
 *   frame as an EGLImage. Nothing is ever read back or copied; the UI thread
 *   binds the image as a texture and composites it. Per-frame CPU cost is
 *   then independent of the window's pixel area, which is the whole point --
 *   the SHM path's cost is a full-frame memcpy plus a raster upload, so it
 *   grows with area and turns fullscreen chrome into visible lag.
 * - SHM (`chromeGpu=false`): wpe_fdo_initialize_shm with WebKit forced off
 *   the GPU entirely. Exports are memcpy'd out on the GLib thread and handed
 *   to the consumer as [ShmFrame]s. Kept as the A/B reference and as the
 *   automatic fallback if the GPU path cannot initialise.
 *
 * In BOTH paths the frame-complete ack fires when the consumer takes a frame,
 * so the page paces itself to the host's actual rate.
 *
 * Threading: all WebKit calls happen on the GLib thread ([Glib.post]);
 * script messages arrive there and are forwarded verbatim to [onMessage].
 */
class WpeChrome(
    private val width: Int,
    private val height: Int,
    /**
     * False forces the SHM path even when the GPU one is available -- the
     * legacy in-loop consumer has no GL context to import into.
     */
    private val allowGpu: Boolean = true,
    private val onMessage: (String) -> Unit,
) {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofShared()
    private lateinit var wpe: SymbolLookup
    private lateinit var fdo: SymbolLookup
    private lateinit var webkit: SymbolLookup
    private lateinit var gobject: SymbolLookup

    private fun fn(l: SymbolLookup, name: String, desc: FunctionDescriptor) =
        linker.downcallHandle(l.find(name).orElseThrow { UnsatisfiedLinkError(name) }, desc)

    /** Whether the host is compositing the chrome right now (EDT writes). */
    @Volatile var visible: Boolean = true


    private var exportable: MemorySegment = MemorySegment.NULL
    private var webView: MemorySegment = MemorySegment.NULL

    @Volatile var framesExported = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /**
     * True once fdo is running on the isolated EGLDisplay and frames are
     * expected as EGLImages. Read by the consumer to pick its import path;
     * only meaningful after [start]'s init has run.
     */
    @Volatile var gpuActive = false
        private set

    /** Woken on every export so the consumer's thread can pick the frame up. */
    @Volatile var onFrame: (() -> Unit)? = null

    // fdo's own wayland connection and EGLDisplay. Deliberately NOT the
    // display GLFW presents on: handing fdo that one froze every window
    // present on NVIDIA (fdo binds the display for its nested compositor and
    // the driver serialises the two uses against each other). fdo is the only
    // thing that ever touches these.
    private var wlDisplay: MemorySegment = MemorySegment.NULL
    private var eglDisplay: MemorySegment = MemorySegment.NULL
    private val wlClient by lazy { SymbolLookup.libraryLookup("libwayland-client.so.0", arena) }
    private val eglLib by lazy { SymbolLookup.libraryLookup("libEGL.so.1", arena) }

    private var pageUri: String = ""

    /**
     * Bumped every time the page is (re)loaded. Upstream gives each playback
     * session a NEW web view (created in createPlayer, destroyed with the
     * player), so its page always starts in bootstrap state: the controls
     * page keys its opening overlay off `!hasReceivedPlayerControls`, and a
     * recycled page has that false -- which is why a reused page shows the
     * PREVIOUS session's controls, and replays its stale playback state back
     * to the app as a spurious pause. Reloading gives the same fresh JS
     * context without paying WPE's process startup again.
     */
    @Volatile var sessionEpoch: Long = 0L
        private set

    /** True while the page has not been fed any state since its last load. */
    @Volatile var pageFresh: Boolean = false
        private set

    /** Reload the controls page, so the next session starts from bootstrap. */
    fun reloadPage() {
        if (pageUri.isEmpty()) return
        sessionEpoch++
        pageFresh = true
        Glib.post {
            runCatching {
                fn(webkit, "webkit_web_view_load_uri", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
                    .invokeExact(webView, arena.allocateFrom(pageUri))
                Unit
            }.onFailure { lastError = it.toString() }
        }
    }

    /** Called when state is pushed into the page: it is no longer pristine. */
    fun markPageUsed() { pageFresh = false }

    // ---- reveal gate -------------------------------------------------------
    //
    // A session must go from click straight to the opening overlay, with
    // nothing in between. Three things used to appear there, all the same
    // mistake in different clothes: showing the chrome before the page had
    // painted THIS session's state. Too early by a page load and it is the
    // bootstrap spinner; too early by a state push and it is last session's
    // controls; too early by a frame and it is black, because the layer
    // dropped its texture when it was hidden.
    //
    // So the gate counts frames the CONSUMER has taken, not frames the page
    // exported: taken means the texture actually holds it, which is the only
    // version of "painted" a reveal can draw.

    /** Frames handed to the consumer; the unit the reveal gate counts in. */
    @Volatile var framesTaken: Long = 0L
        private set

    private val revealAfterTaken = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)

    /** True once the page has painted -- and the layer holds -- what it was armed for. */
    val revealReady: Boolean get() = framesTaken >= revealAfterTaken.get()

    /** Shut the gate: nothing painted so far may be shown. */
    fun closeReveal() { revealAfterTaken.set(Long.MAX_VALUE) }

    /**
     * Open the gate three frames from now -- once the page has painted what
     * was just pushed into it.
     *
     * Three, not one: at the moment of a push one frame can be parked waiting
     * to be taken and another can already be rendering, and both of those
     * predate the push. The third cannot.
     *
     * Arms only from closed. Re-arming an open gate would hide chrome that is
     * already on screen, which is a flash of its own.
     */
    fun armReveal() {
        revealAfterTaken.compareAndSet(Long.MAX_VALUE, framesTaken + 3)
    }

    /**
     * Park exports for the consumer even while hidden, so a reveal has
     * something to draw the instant it happens.
     *
     * Set for the session-start window only. Outside it a hidden page's
     * frames are worthless -- they are the previous session's -- and dropping
     * them is what keeps a hidden chrome free.
     */
    @Volatile var priming: Boolean = false

    fun start(pageUri: String) {
        this.pageUri = pageUri
        pageFresh = true
        Glib.ensureStarted()
        Glib.post {
            runCatching { init(pageUri) }.onFailure {
                lastError = it.toString()
                it.printStackTrace()
            }
        }
    }

    private fun init(pageUri: String) {
        wpe = SymbolLookup.libraryLookup("libwpe-1.0.so.1", arena)
        fdo = SymbolLookup.libraryLookup("libWPEBackend-fdo-1.0.so.1", arena)

        // libwpe resolves its backend through the loader; without this it
        // hunts for a default and WebKit aborts on a missing backend.
        fn(wpe, "wpe_loader_init", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS))
            .invokeExact(arena.allocateFrom("libWPEBackend-fdo-1.0.so.1")) as Boolean

        // GPU first: fdo on its OWN EGLDisplay, so WebKit composites on the
        // GPU and exports EGLImages we never read back. Any failure degrades
        // to the software path rather than taking the app down.
        gpuActive = allowGpu && runCatching { initEglDisplay() }.getOrElse {
            lastError = "gpu init: $it"
            println("[wpe] GPU path unavailable ($it) -- falling back to SHM")
            false
        }

        if (!gpuActive) {
            // Pure software WPE: the nested compositor offers only wl_shm and
            // the WEBKIT_DISABLE_* env keeps the web process off GL entirely.
            // This is what upstream's linux branch ships -- its
            // player_bridge.cpp documents the same NVIDIA failure ("DMABUF
            // renderer yields controls snapshots with degraded alpha ...
            // fully opaque"). Those two variables used to be set
            // unconditionally by the Gradle run task, which forced software
            // WebKit even when nothing wanted it; they are set here instead,
            // for the SHM path only, and still before any web process spawns.
            setEnv("WEBKIT_DISABLE_DMABUF_RENDERER", "1")
            setEnv("WEBKIT_DISABLE_COMPOSITING_MODE", "1")
            check(
                fn(fdo, "wpe_fdo_initialize_shm", FunctionDescriptor.of(JAVA_BOOLEAN))
                    .invokeExact() as Boolean,
            ) { "wpe_fdo_initialize_shm failed" }
        }
        println("[wpe] export path: ${if (gpuActive) "GPU (EGLImage, zero-copy)" else "SHM (software raster)"}")

        // struct wpe_view_backend_exportable_fdo_egl_client:
        //   [0] export_egl_image (legacy)  [1] export_fdo_egl_image  [2] shm  [3,4] reserved
        // Slot ONE is the live EGL export; slot zero is the deprecated
        // raw-EGLImage variant and filling it yields a silent black view.
        val client = arena.allocate(MemoryLayout.sequenceLayout(5, ADDRESS))
        val shmStub = linker.upcallStub(
            MethodHandles.lookup().findStatic(
                WpeCallbacks::class.java, "exportShmBuffer",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java),
            ),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), arena,
        )
        // Filled even on the SHM path: harmless there (fdo never calls it),
        // and it means a GPU-mode run that somehow still produces SHM buffers
        // is handled rather than silently blank.
        val eglStub = linker.upcallStub(
            MethodHandles.lookup().findStatic(
                WpeCallbacks::class.java, "exportEglImage",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java),
            ),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), arena,
        )
        WpeCallbacks.owner = this
        client.setAtIndex(ADDRESS, 0, MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 1, if (gpuActive) eglStub else MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 2, shmStub)
        client.setAtIndex(ADDRESS, 3, MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 4, MemorySegment.NULL)

        exportable = fn(fdo, "wpe_view_backend_exportable_fdo_egl_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT))
            .invokeExact(client, MemorySegment.NULL, width, height) as MemorySegment
        viewBackend = fn(fdo, "wpe_view_backend_exportable_fdo_get_view_backend",
            FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(exportable) as MemorySegment

        // The web process must never touch the NVIDIA driver: its GL->SHM
        // readback garbles the alpha channel there (chrome became an opaque
        // black sheet), and any GPU work it does contends with mpv's render
        // thread. Compositing stays ON (that is what sizes buffers at
        // logical*scale, the crisp path); it just runs on Mesa's software
        // rasterizer. Set here -- after every NVIDIA context this process
        // needs already exists, before the web process is spawned; children
        // inherit the environment at fork.
        if (System.getProperty("nuvio.wayland.chromeSoftware")?.toBoolean() == true) {
            setEnv("LIBGL_ALWAYS_SOFTWARE", "1")
            setEnv("GALLIUM_DRIVER", "llvmpipe")
            setEnv("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/50_mesa.json")
        }

        // WebKit only after the backend exists; its libraries pull in the
        // loader state established above.
        webkit = SymbolLookup.libraryLookup("libWPEWebKit-2.0.so.1", arena)
        gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena)

        val wkBackend = fn(webkit, "webkit_web_view_backend_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))
            .invokeExact(viewBackend, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        webView = fn(webkit, "webkit_web_view_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(wkBackend) as MemorySegment

        // The page's transport: webkit.messageHandlers.player.postMessage(...)
        // -- the same handler name and payload shape the stock GTK bridge
        // registers, so controls.js runs unmodified.
        val ucm = fn(webkit, "webkit_web_view_get_user_content_manager",
            FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(webView) as MemorySegment
        val msgStub = linker.upcallStub(
            MethodHandles.lookup().findStatic(
                WpeCallbacks::class.java, "playerMessage",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java,
                    MemorySegment::class.java, MemorySegment::class.java,
                ),
            ),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), arena,
        )
        fn(gobject, "g_signal_connect_data",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
            .invokeExact(
                ucm, arena.allocateFrom("script-message-received::player"),
                msgStub, MemorySegment.NULL, MemorySegment.NULL, 0,
            ) as Long
        fn(webkit, "webkit_user_content_manager_register_script_message_handler",
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS))
            .invokeExact(ucm, arena.allocateFrom("player"), MemorySegment.NULL) as Boolean

        // Transparent background, or the view is an opaque wall: at launch it
        // blacked out the whole app, and in playback it sat as solid black
        // between the video below and the chrome the page draws on top --
        // "player UI works, no video, only sound". Stock builds its overlay
        // window transparent for the same reason. WebKitColor = 4 gdoubles.
        Arena.ofConfined().use { a ->
            val color = a.allocate(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 4)
            if (System.getProperty("nuvio.wayland.chromeBgRed")?.toBoolean() == true) {
                color.setAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0, 1.0)
                color.setAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 3, 1.0)
            }
            // Statement position on purpose: a void invokeExact in a lambda's
            // tail is compiled expecting Object and throws
            // WrongMethodTypeException at runtime.
            fn(webkit, "webkit_web_view_set_background_color",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
                .invokeExact(webView, color)
            Unit
        }

        fn(webkit, "webkit_web_view_load_uri", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
            .invokeExact(webView, arena.allocateFrom(pageUri))
        println("[wpe] view up, loading $pageUri")
    }

    /**
     * Bring up fdo's own wayland connection and EGLDisplay and hand THAT to
     * `wpe_fdo_initialize_for_egl_display`. Returns true when WebKit will
     * export EGLImages.
     *
     * The isolation is the load-bearing part. `wpe_fdo_initialize_for_egl_display`
     * on the display GLFW presents on froze every window present on NVIDIA, so
     * fdo gets a connection nothing else shares.
     *
     * That the resulting images are still importable from the window's GL
     * context -- they belong to a different EGLDisplay, which the EGL spec does
     * not promise is legal -- was verified against this driver before the path
     * was written: two wayland connections, two EGLDisplays, an image created
     * on one and bound with glEGLImageTargetTexture2DOES in a GL 3.3 core
     * context on the other, read back with matching pixels. The consumer still
     * checks at runtime and falls back rather than trusting that.
     */
    private fun initEglDisplay(): Boolean {
        wlDisplay = fn(wlClient, "wl_display_connect", FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(MemorySegment.NULL) as MemorySegment
        check(!wlDisplay.equals(MemorySegment.NULL)) { "wl_display_connect(NULL) failed" }

        // EGL_PLATFORM_WAYLAND_KHR. eglGetPlatformDisplay is EGL 1.5 core, and
        // this driver reports 1.5, so no EXT/proc-address dance is needed.
        eglDisplay = fn(eglLib, "eglGetPlatformDisplay",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
            .invokeExact(0x31D8, wlDisplay, MemorySegment.NULL) as MemorySegment
        check(!eglDisplay.equals(MemorySegment.NULL)) { "eglGetPlatformDisplay failed" }

        val initialised = Arena.ofConfined().use { a ->
            val major = a.allocate(JAVA_INT)
            val minor = a.allocate(JAVA_INT)
            val ok = fn(eglLib, "eglInitialize",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
                .invokeExact(eglDisplay, major, minor) as Int
            if (ok != 0) {
                println("[wpe] fdo EGLDisplay ${major.get(JAVA_INT, 0)}.${minor.get(JAVA_INT, 0)} (isolated connection)")
            }
            ok != 0
        }
        check(initialised) { "eglInitialize on fdo's display failed" }

        check(
            fn(fdo, "wpe_fdo_initialize_for_egl_display",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS))
                .invokeExact(eglDisplay) as Boolean,
        ) { "wpe_fdo_initialize_for_egl_display failed" }
        return true
    }

    /**
     * setenv(3) in this process. Called before WebKit's libraries load and
     * before any web process is spawned, so children inherit it at fork --
     * which is the only reason these can be set from Kotlin at all.
     */
    private fun setEnv(key: String, value: String) {
        val setenv = linker.downcallHandle(
            linker.defaultLookup().find("setenv").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
        )
        Arena.ofConfined().use { a ->
            setenv.invokeExact(a.allocateFrom(key), a.allocateFrom(value), 1) as Int
            Unit
        }
    }

    // ---- GLib-thread callbacks ----

    private val wlServer by lazy { SymbolLookup.libraryLookup("libwayland-server.so.0", arena) }

    /** A copied chrome frame: tightly packed BGRA (wl ARGB8888) pixels. */
    class ShmFrame(val width: Int, val height: Int, val pixels: java.nio.ByteBuffer)

    private val pendingShm = java.util.concurrent.atomic.AtomicReference<ShmFrame?>(null)

    /** Newest exported EGLImage awaiting import (GPU path). */
    private val pendingImage =
        java.util.concurrent.atomic.AtomicReference<MemorySegment?>(null)

    // Ack conservation: dispatch_frame_complete is only ever valid as the
    // answer to one export. Un-paired acks sent WPE into a render storm --
    // one logged burst re-rendered the page 133k times in seconds.
    private val ackOwed = java.util.concurrent.atomic.AtomicBoolean(false)

    // Hot-path handles, resolved once. The SHM path rebuilds a downcall handle
    // per call per frame; on the GPU path these run on every exported frame,
    // so they are cached.
    private val hGetEglImage by lazy {
        fn(fdo, "wpe_fdo_egl_exported_image_get_egl_image", FunctionDescriptor.of(ADDRESS, ADDRESS))
    }
    private val hImageWidth by lazy {
        fn(fdo, "wpe_fdo_egl_exported_image_get_width", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }
    private val hImageHeight by lazy {
        fn(fdo, "wpe_fdo_egl_exported_image_get_height", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }
    private val hReleaseImage by lazy {
        fn(fdo, "wpe_view_backend_exportable_fdo_egl_dispatch_release_exported_image",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    }
    private val hFrameComplete by lazy {
        fn(fdo, "wpe_view_backend_exportable_fdo_dispatch_frame_complete",
            FunctionDescriptor.ofVoid(ADDRESS))
    }

    /**
     * GPU export. Nothing is copied: the image is parked for the consumer,
     * which binds it as a texture and releases it once its successor is bound.
     *
     * Frame-complete and buffer-release stay DISTINCT here, exactly as on the
     * SHM path -- conflating them deadlocks after one frame. The ack is owed
     * until the consumer takes the frame; the release is a separate lifetime
     * question about the buffer's memory.
     */
    internal fun handleEglExport(image: MemorySegment) {
        framesExported++
        ackOwed.set(true)
        if (!visible && !priming) {
            // Nothing will ever bind it, so it goes straight back. The slow
            // trickle keeps the page's rAF logic alive at ~zero cost; an
            // instant ack re-ran the renderer flat out.
            releaseImage(image)
            Glib.postDelayed(200) { dispatchFrameComplete() }
            return
        }
        // Newest wins. A predecessor still sitting here was never bound by the
        // consumer, so it is safe to hand back immediately; the one the
        // consumer HAS bound is released by the consumer, after its successor
        // is bound (an EGLImage released while still sampled is undefined).
        pendingImage.getAndSet(image)?.let { releaseImage(it) }
        onFrame?.invoke()
    }

    /** Newest exported image for the consumer; caller must [releaseImageAsync] it. */
    fun takeEglImage(): MemorySegment? =
        pendingImage.getAndSet(null)?.also { framesTaken++ }

    /** EGLImageKHR handle inside [image]; valid until the image is released. */
    fun eglImageOf(image: MemorySegment): MemorySegment =
        hGetEglImage.invokeExact(image) as MemorySegment

    fun imageWidth(image: MemorySegment): Int = hImageWidth.invokeExact(image) as Int

    fun imageHeight(image: MemorySegment): Int = hImageHeight.invokeExact(image) as Int

    /**
     * What a dmabuf export describes. Single plane only, which is what WPE
     * hands out for a rendered page -- [exportDmabuf] refuses anything else
     * rather than importing a plane and calling it a frame.
     *
     * [fd] is owned by the caller and must be closed once imported.
     */
    class Dmabuf(
        val fd: Int,
        val fourcc: Int,
        val modifier: Long,
        val stride: Int,
        val offset: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Export [image] as a DMABUF, for a consumer that is not GL.
     *
     * EGL_MESA_image_dma_buf_export is the only route out of an EGLImage that
     * does not involve a readback, and it is an extension of fdo's own
     * isolated display, which is the display this image belongs to. Null when
     * the driver lacks it or the image is not single-plane.
     */
    fun exportDmabuf(image: MemorySegment): Dmabuf? {
        val query = eglExportQuery ?: return null
        val export = eglExport ?: return null
        val egl = eglImageOf(image)
        if (egl.equals(MemorySegment.NULL)) return null
        return Arena.ofConfined().use { a ->
            val fourcc = a.allocate(JAVA_INT)
            val numPlanes = a.allocate(JAVA_INT)
            val modifiers = a.allocate(JAVA_LONG)
            val ok = query.invokeExact(eglDisplay, egl, fourcc, numPlanes, modifiers) as Int
            if (ok == 0) {
                if (!loggedExportFail) {
                    loggedExportFail = true
                    System.err.println("[wpe] eglExportDMABUFImageQueryMESA failed")
                }
                return@use null
            }
            val planes = numPlanes.get(JAVA_INT, 0)
            if (planes != 1) {
                if (!loggedExportFail) {
                    loggedExportFail = true
                    System.err.println("[wpe] dmabuf export has $planes planes; only 1 is handled")
                }
                return@use null
            }
            val fds = a.allocate(JAVA_INT)
            val strides = a.allocate(JAVA_INT)
            val offsets = a.allocate(JAVA_INT)
            val ok2 = export.invokeExact(eglDisplay, egl, fds, strides, offsets) as Int
            if (ok2 == 0) {
                if (!loggedExportFail) {
                    loggedExportFail = true
                    System.err.println("[wpe] eglExportDMABUFImageMESA failed")
                }
                return@use null
            }
            Dmabuf(
                fd = fds.get(JAVA_INT, 0),
                fourcc = fourcc.get(JAVA_INT, 0),
                modifier = modifiers.get(JAVA_LONG, 0),
                stride = strides.get(JAVA_INT, 0),
                offset = offsets.get(JAVA_INT, 0),
                width = imageWidth(image),
                height = imageHeight(image),
            )
        }
    }

    private var loggedExportFail = false

    /** Both are extension entry points, so they come from eglGetProcAddress. */
    private val eglExportQuery: java.lang.invoke.MethodHandle? by lazy { eglProc(
        "eglExportDMABUFImageQueryMESA",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    ) }
    private val eglExport: java.lang.invoke.MethodHandle? by lazy { eglProc(
        "eglExportDMABUFImageMESA",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    ) }

    private fun eglProc(name: String, desc: FunctionDescriptor): java.lang.invoke.MethodHandle? {
        val getProc = fn(eglLib, "eglGetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val addr = Arena.ofConfined().use { a ->
            getProc.invokeExact(a.allocateFrom(name)) as MemorySegment
        }
        if (addr.equals(MemorySegment.NULL)) return null
        return linker.downcallHandle(addr, desc)
    }

    /** Hand an image back to WPE. Safe from any thread. */
    fun releaseImageAsync(image: MemorySegment) {
        Glib.post { releaseImage(image) }
    }

    private fun releaseImage(image: MemorySegment) {
        hReleaseImage.invokeExact(exportable, image)
    }

    internal fun handleShmExport(buffer: MemorySegment) {
        framesExported++
        ackOwed.set(true)
        var handedOff = false
        runCatching {
            if (!visible && !priming) return@runCatching // skip the copy entirely
            val shm = fn(fdo, "wpe_fdo_shm_exported_buffer_get_shm_buffer",
                FunctionDescriptor.of(ADDRESS, ADDRESS)).invokeExact(buffer) as MemorySegment
            if (!shm.equals(MemorySegment.NULL)) {
                fn(wlServer, "wl_shm_buffer_begin_access", FunctionDescriptor.ofVoid(ADDRESS))
                    .invokeExact(shm)
                val data = fn(wlServer, "wl_shm_buffer_get_data",
                    FunctionDescriptor.of(ADDRESS, ADDRESS)).invokeExact(shm) as MemorySegment
                val stride = fn(wlServer, "wl_shm_buffer_get_stride",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(shm) as Int
                val w = fn(wlServer, "wl_shm_buffer_get_width",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(shm) as Int
                val h = fn(wlServer, "wl_shm_buffer_get_height",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(shm) as Int
                if (!data.equals(MemorySegment.NULL) && w > 0 && h > 0) {
                    val out = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                    val outSeg = MemorySegment.ofBuffer(out)
                    val src = data.reinterpret(stride.toLong() * h)
                    if (stride == w * 4) {
                        MemorySegment.copy(src, 0, outSeg, 0, w.toLong() * h * 4)
                    } else {
                        for (row in 0 until h) {
                            MemorySegment.copy(
                                src, row.toLong() * stride,
                                outSeg, row.toLong() * w * 4, w.toLong() * 4,
                            )
                        }
                    }
                    pendingShm.set(ShmFrame(w, h, out))
                    handedOff = true
                }
                fn(wlServer, "wl_shm_buffer_end_access", FunctionDescriptor.ofVoid(ADDRESS))
                    .invokeExact(shm)
            }
        }.onFailure { lastError = it.toString() }
        fn(fdo, "wpe_view_backend_exportable_fdo_dispatch_release_shm_exported_buffer",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
            .invokeExact(exportable, buffer)
        if (handedOff) {
            // The ack comes when the consumer takes the frame: the page paces
            // itself to the host's actual rate.
            onFrame?.invoke()
        } else {
            // Hidden or inactive: a slow trickle, never instant -- an
            // instant ack re-ran the renderer flat out (~90fps of CPU
            // raster). 5fps keeps the page's rAF-driven logic alive so it
            // can still react (reveal chrome, run its fade) at ~zero cost.
            Glib.postDelayed(200) { dispatchFrameComplete() }
        }
    }

    /** Newest chrome frame for the EDT; caller must [ackFrame] after use. */
    fun takeShmFrame(): ShmFrame? = pendingShm.getAndSet(null)?.also { framesTaken++ }


    internal fun handleMessage(jscValue: MemorySegment) {
        val json = fn(webkit, "jsc_value_to_json",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
            .invokeExact(jscValue, 0) as MemorySegment
        if (!json.equals(MemorySegment.NULL)) {
            onMessage(json.reinterpret(Long.MAX_VALUE).getString(0))
        }
    }

    /**
     * Acknowledge the current frame so WPE renders the next one. The EDT
     * calls this after consuming a frame; it is also the kick after
     * unhiding, and any other stall recovery.
     */
    fun ackFrame() {
        Glib.post { dispatchFrameComplete() }
    }

    /** Ack after a delay: the page cannot render faster than 1000/ms fps. */
    fun ackFrameAfter(ms: Int) {
        Glib.postDelayed(ms) { dispatchFrameComplete() }
    }

    private fun dispatchFrameComplete() {
        if (!ackOwed.getAndSet(false)) return
        hFrameComplete.invokeExact(exportable)
    }

    // ---- input, forwarded from the host's GLFW callbacks ----
    // Layouts from wpe/input.h: pointer/axis are 7 ints; keyboard is
    // 3 ints + bool + pad + int. key_code is an XKB keysym.

    private val pointerLayout = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
    )

    fun dispatchPointer(motion: Boolean, x: Int, y: Int, button: Int, pressed: Boolean, modifiers: Int) {
        Glib.post {
            val backend = viewBackendOrNull() ?: return@post
            Arena.ofConfined().use { a ->
                val ev = a.allocate(pointerLayout)
                ev.set(JAVA_INT, 0, if (motion) 1 else 2) // motion / button
                ev.set(JAVA_INT, 4, (System.nanoTime() / 1_000_000L).toInt())
                ev.set(JAVA_INT, 8, x)
                ev.set(JAVA_INT, 12, y)
                ev.set(JAVA_INT, 16, button)
                ev.set(JAVA_INT, 20, if (pressed) 1 else 0)
                ev.set(JAVA_INT, 24, modifiers)
                fn(wpe, "wpe_view_backend_dispatch_pointer_event",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
                    .invokeExact(backend, ev)
                Unit // see set_background_color note
            }
        }
    }

    fun dispatchAxis(x: Int, y: Int, vertical: Boolean, value: Int) {
        Glib.post {
            val backend = viewBackendOrNull() ?: return@post
            Arena.ofConfined().use { a ->
                val ev = a.allocate(pointerLayout) // same 7-int shape
                ev.set(JAVA_INT, 0, 1) // motion
                ev.set(JAVA_INT, 4, (System.nanoTime() / 1_000_000L).toInt())
                ev.set(JAVA_INT, 8, x)
                ev.set(JAVA_INT, 12, y)
                ev.set(JAVA_INT, 16, if (vertical) 0 else 1) // axis
                ev.set(JAVA_INT, 20, value)
                ev.set(JAVA_INT, 24, 0)
                fn(wpe, "wpe_view_backend_dispatch_axis_event",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
                    .invokeExact(backend, ev)
                Unit // see set_background_color note
            }
        }
    }

    private val keyboardLayout = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_BOOLEAN,
        MemoryLayout.paddingLayout(3), JAVA_INT,
    )

    fun dispatchKey(keysym: Int, hardware: Int, pressed: Boolean, modifiers: Int) {
        Glib.post {
            val backend = viewBackendOrNull() ?: return@post
            Arena.ofConfined().use { a ->
                val ev = a.allocate(keyboardLayout)
                ev.set(JAVA_INT, 0, (System.nanoTime() / 1_000_000L).toInt())
                ev.set(JAVA_INT, 4, keysym)
                ev.set(JAVA_INT, 8, hardware)
                ev.set(JAVA_BOOLEAN, 12, pressed)
                ev.set(JAVA_INT, 16, modifiers)
                fn(wpe, "wpe_view_backend_dispatch_keyboard_event",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
                    .invokeExact(backend, ev)
                Unit // see set_background_color note
            }
        }
    }

    fun dispatchSize(w: Int, h: Int) {
        Glib.post {
            val backend = viewBackendOrNull() ?: return@post
            fn(wpe, "wpe_view_backend_dispatch_set_size",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT))
                .invokeExact(backend, w, h)
        }
    }

    /**
     * Device pixels per CSS pixel. With this set the page keeps its logical
     * size and coordinates but rasters (and exports) at physical resolution
     * -- the difference between soft and crisp chrome on a scaled output.
     */
    fun dispatchScale(scale: Float) {
        Glib.post {
            val backend = viewBackendOrNull() ?: return@post
            fn(wpe, "wpe_view_backend_dispatch_set_device_scale_factor",
                FunctionDescriptor.ofVoid(ADDRESS, java.lang.foreign.ValueLayout.JAVA_FLOAT))
                .invokeExact(backend, scale)
        }
    }

    private var viewBackend: MemorySegment = MemorySegment.NULL
    private fun viewBackendOrNull(): MemorySegment? =
        viewBackend.takeIf { !it.equals(MemorySegment.NULL) }

    /** Push state into the page (stock evaluates scripts the same way). */
    fun evaluateJs(script: String) {
        Glib.post {
            Arena.ofConfined().use { a ->
                fn(webkit, "webkit_web_view_evaluate_javascript",
                    FunctionDescriptor.ofVoid(
                        ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                    ))
                    .invokeExact(
                        webView, a.allocateFrom(script), -1L,
                        MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    )
                Unit // see set_background_color note
            }
        }
    }
}

/** FFM upcalls must target static methods. */
internal object WpeCallbacks {
    @JvmStatic
    var owner: WpeChrome? = null

    @JvmStatic
    fun playerMessage(ucm: MemorySegment, jscValue: MemorySegment, data: MemorySegment) {
        owner?.handleMessage(jscValue)
    }

    @JvmStatic
    fun exportShmBuffer(data: MemorySegment, buffer: MemorySegment) {
        owner?.handleShmExport(buffer)
    }

    @JvmStatic
    fun exportEglImage(data: MemorySegment, image: MemorySegment) {
        owner?.handleEglExport(image)
    }
}
