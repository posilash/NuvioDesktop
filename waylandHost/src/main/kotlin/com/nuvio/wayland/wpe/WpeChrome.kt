package com.nuvio.wayland.wpe

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
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
 * Built on WPEPlatform's headless display. WebKit 2.54 as Arch ships it has
 * no libwpe/WPEBackend-fdo API left, and headless is the platform that draws
 * nowhere on its own: every frame reaches [handleBuffer] through the view's
 * render_buffer, which is overridden here (see [overrideRenderBuffer]).
 *
 * Two buffer kinds, chosen by `-Dnuvio.wayland.chromeGpu` (default true):
 *
 * - GPU: WebKit renders on [drmDevice] and each frame is a dmabuf. Nothing is
 *   copied; the consumer imports it and composites it. Per-frame cost is then
 *   independent of the window's pixel area.
 * - SHM: WebKit is forced off the GPU and frames are shared-memory buffers,
 *   copied out on the GLib thread and handed over as [ShmFrame]s. The A/B
 *   reference, and what a run without the GPU path falls back to.
 *
 * In both, `wpe_view_buffer_rendered` -- WebKit's frame-done -- is sent when
 * the consumer takes a frame, so the page paces itself to the host's rate.
 *
 * Threading: all WebKit calls happen on the GLib thread ([Glib.post]);
 * script messages arrive there and are forwarded verbatim to [onMessage].
 */
class WpeChrome(
    private val width: Int,
    private val height: Int,
    /**
     * False forces the SHM path even when the GPU one is available -- the
     * legacy in-loop consumer has nothing to import a dmabuf into.
     */
    private val allowGpu: Boolean = true,
    /**
     * Render node WebKit draws on. It has to be the GPU that presents: on a
     * hybrid machine headless picks the first device it finds, and a buffer
     * tiled for the iGPU cannot be imported by the discrete one.
     */
    private val drmDevice: String? = null,
    private val onMessage: (String) -> Unit,
) {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofShared()
    private lateinit var webkit: SymbolLookup
    private val gobject by lazy { SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena) }
    private val glib by lazy { SymbolLookup.libraryLookup("libglib-2.0.so.0", arena) }

    private fun fn(l: SymbolLookup, name: String, desc: FunctionDescriptor) =
        linker.downcallHandle(l.find(name).orElseThrow { UnsatisfiedLinkError(name) }, desc)

    /** Whether the host is compositing the chrome right now (EDT writes). */
    @Volatile var visible: Boolean = true

    private var webView: MemorySegment = MemorySegment.NULL
    private var view: MemorySegment = MemorySegment.NULL
    private var toplevel: MemorySegment = MemorySegment.NULL

    @Volatile var framesExported = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /**
     * True when frames are expected as dmabufs. Read by the consumer to pick
     * its import path; it drops to false if WebKit hands over SHM instead.
     */
    @Volatile var gpuActive = false
        private set

    /** Woken on every export so the consumer's thread can pick the frame up. */
    @Volatile var onFrame: (() -> Unit)? = null

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
        if (!allowGpu) {
            // Set before WebKit loads and before any web process is spawned;
            // children inherit the environment at fork.
            setEnv("WEBKIT_DISABLE_DMABUF_RENDERER", "1")
            setEnv("WEBKIT_DISABLE_COMPOSITING_MODE", "1")
        }
        if (System.getProperty("nuvio.wayland.chromeSoftware")?.toBoolean() == true) {
            setEnv("LIBGL_ALWAYS_SOFTWARE", "1")
            setEnv("GALLIUM_DRIVER", "llvmpipe")
            setEnv("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/50_mesa.json")
        }

        webkit = SymbolLookup.libraryLookup("libWPEWebKit-2.0.so.1", arena)
        overrideRenderBuffer()

        val display = Arena.ofConfined().use { a ->
            val error = a.allocate(ADDRESS)
            var display = MemorySegment.NULL
            if (allowGpu && drmDevice != null) {
                display = fn(webkit, "wpe_display_headless_new_for_device",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
                    .invokeExact(a.allocateFrom(drmDevice), error) as MemorySegment
                if (display.equals(MemorySegment.NULL)) {
                    println("[wpe] $drmDevice unusable (${errorMessage(error)}); letting headless pick")
                }
            }
            if (display.equals(MemorySegment.NULL)) {
                display = fn(webkit, "wpe_display_headless_new", FunctionDescriptor.of(ADDRESS))
                    .invokeExact() as MemorySegment
            }
            error.set(ADDRESS, 0, MemorySegment.NULL)
            val connected = fn(webkit, "wpe_display_connect",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
                .invokeExact(display, error) as Int
            check(connected != 0) { "wpe_display_connect: ${errorMessage(error)}" }
            display
        }
        gpuActive = allowGpu
        println("[wpe] headless display on ${if (allowGpu) drmDevice ?: "default device" else "SHM"}")

        val webViewType = fn(webkit, "webkit_web_view_get_type", FunctionDescriptor.of(JAVA_LONG))
            .invokeExact() as Long
        val gObjectNew = linker.downcallHandle(
            gobject.find("g_object_new").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS),
            Linker.Option.firstVariadicArg(2),
        )
        webView = gObjectNew.invokeExact(
            webViewType, arena.allocateFrom("display"), display, MemorySegment.NULL,
        ) as MemorySegment
        view = fn(webkit, "webkit_web_view_get_wpe_view", FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(webView) as MemorySegment
        toplevel = fn(webkit, "wpe_view_get_toplevel", FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(view) as MemorySegment
        check(!toplevel.equals(MemorySegment.NULL)) { "headless view came without a toplevel" }
        resize(width, height)
        fn(webkit, "wpe_view_focus_in", FunctionDescriptor.ofVoid(ADDRESS)).invokeExact(view)

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
        // between the video below and the chrome the page draws on top.
        // WebKitColor = 4 gdoubles.
        Arena.ofConfined().use { a ->
            val color = a.allocate(JAVA_DOUBLE, 4)
            if (System.getProperty("nuvio.wayland.chromeBgRed")?.toBoolean() == true) {
                color.setAtIndex(JAVA_DOUBLE, 0, 1.0)
                color.setAtIndex(JAVA_DOUBLE, 3, 1.0)
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
     * Take over WPEViewHeadless's render_buffer. Its own implementation acks
     * on a fixed 60Hz timer and releases each buffer the moment the next one
     * lands, which leaves a buffer we are still sampling free for WebKit to
     * draw into. Owning the vfunc puts both back in the consumer's hands --
     * the same contract fdo's exportable had.
     *
     * The class is process-wide, but this process creates no other headless
     * view.
     */
    private fun overrideRenderBuffer() {
        val type = fn(webkit, "wpe_view_headless_get_type", FunctionDescriptor.of(JAVA_LONG))
            .invokeExact() as Long
        val klass = (fn(gobject, "g_type_class_ref", FunctionDescriptor.of(ADDRESS, JAVA_LONG))
            .invokeExact(type) as MemorySegment).reinterpret(RENDER_BUFFER_OFFSET + ADDRESS.byteSize())
        check(!klass.get(ADDRESS, RENDER_BUFFER_OFFSET).equals(MemorySegment.NULL)) {
            "WPEViewClass.render_buffer is not where it was; the struct layout changed"
        }
        val stub = linker.upcallStub(
            MethodHandles.lookup().findStatic(
                WpeCallbacks::class.java, "renderBuffer",
                MethodType.methodType(
                    Int::class.java, MemorySegment::class.java, MemorySegment::class.java,
                    MemorySegment::class.java, Int::class.java, MemorySegment::class.java,
                ),
            ),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS), arena,
        )
        WpeCallbacks.owner = this
        klass.set(ADDRESS, RENDER_BUFFER_OFFSET, stub)
    }

    private fun errorMessage(error: MemorySegment): String {
        val e = error.get(ADDRESS, 0)
        if (e.equals(MemorySegment.NULL)) return "unknown error"
        val message = e.reinterpret(16).get(ADDRESS, 8)
        return if (message.equals(MemorySegment.NULL)) "unknown error"
        else message.reinterpret(Long.MAX_VALUE).getString(0)
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

    /** A copied chrome frame: tightly packed BGRA (ARGB8888) pixels. */
    class ShmFrame(val width: Int, val height: Int, val pixels: java.nio.ByteBuffer)

    private val pendingShm = java.util.concurrent.atomic.AtomicReference<ShmFrame?>(null)

    /** Newest dmabuf WPEBuffer awaiting import, holding a reference. */
    private val pendingBuffer =
        java.util.concurrent.atomic.AtomicReference<MemorySegment?>(null)

    // Ack conservation: frame-done is only ever valid as the answer to one
    // export. Un-paired acks sent WPE into a render storm -- one logged burst
    // re-rendered the page 133k times in seconds. Holds a reference.
    private val ackOwed = java.util.concurrent.atomic.AtomicReference<MemorySegment?>(null)

    private val hRef by lazy { fn(gobject, "g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
    private val hUnref by lazy { fn(gobject, "g_object_unref", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val hIsA by lazy {
        fn(gobject, "g_type_check_instance_is_a", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
    }
    private val dmabufType by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_type", FunctionDescriptor.of(JAVA_LONG)).invokeExact() as Long
    }
    private val shmType by lazy {
        fn(webkit, "wpe_buffer_shm_get_type", FunctionDescriptor.of(JAVA_LONG)).invokeExact() as Long
    }
    private val hRendered by lazy {
        fn(webkit, "wpe_view_buffer_rendered", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    }
    private val hReleased by lazy {
        fn(webkit, "wpe_view_buffer_released", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    }
    private val hWidth by lazy { fn(webkit, "wpe_buffer_get_width", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val hHeight by lazy { fn(webkit, "wpe_buffer_get_height", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val hPlanes by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_n_planes", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }
    private val hFd by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_fd", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val hFormat by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_format", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }
    private val hModifier by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_modifier", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    }
    private val hStride by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_stride", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val hOffset by lazy {
        fn(webkit, "wpe_buffer_dma_buf_get_offset", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val hDup by lazy {
        linker.downcallHandle(linker.defaultLookup().find("dup").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    }

    private fun isA(instance: MemorySegment, type: Long) =
        (hIsA.invokeExact(instance, type) as Int) != 0

    private fun ref(o: MemorySegment): MemorySegment = hRef.invokeExact(o) as MemorySegment

    private fun unref(o: MemorySegment) { hUnref.invokeExact(o) }

    /** WebKit's render_buffer; [buffer] is borrowed, so anything kept is ref'd. */
    internal fun handleBuffer(buffer: MemorySegment) {
        framesExported++
        ackOwed.getAndSet(ref(buffer))?.let { unref(it) }
        if (isA(buffer, dmabufType)) {
            if (!visible && !priming) {
                // Nothing will ever import it, so it goes straight back. The
                // slow trickle keeps the page's rAF logic alive at ~zero cost;
                // an instant ack re-ran the renderer flat out.
                released(buffer)
                Glib.postDelayed(200) { dispatchFrameComplete() }
                return
            }
            // Newest wins. A predecessor still parked was never imported, so it
            // goes back now; the one the consumer HAS imported is released by
            // the consumer once its successor is on screen.
            pendingBuffer.getAndSet(ref(buffer))?.let { release(it) }
            onFrame?.invoke()
            return
        }
        if (isA(buffer, shmType)) {
            if (gpuActive) {
                gpuActive = false
                println("[wpe] WebKit is handing over SHM buffers; using the SHM path")
            }
            val handedOff = (visible || priming) && runCatching { copyShm(buffer) }
                .onFailure { lastError = it.toString() }.getOrDefault(false)
            released(buffer)
            if (handedOff) onFrame?.invoke() else Glib.postDelayed(200) { dispatchFrameComplete() }
            return
        }
        lastError = "unhandled WPEBuffer type"
        released(buffer)
        Glib.postDelayed(200) { dispatchFrameComplete() }
    }

    private fun copyShm(buffer: MemorySegment): Boolean {
        val bytes = fn(webkit, "wpe_buffer_shm_get_data", FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(buffer) as MemorySegment
        if (bytes.equals(MemorySegment.NULL)) return false
        val stride = fn(webkit, "wpe_buffer_shm_get_stride", FunctionDescriptor.of(JAVA_INT, ADDRESS))
            .invokeExact(buffer) as Int
        val w = hWidth.invokeExact(buffer) as Int
        val h = hHeight.invokeExact(buffer) as Int
        if (w <= 0 || h <= 0) return false
        val data = Arena.ofConfined().use { a ->
            val size = a.allocate(JAVA_LONG)
            (fn(glib, "g_bytes_get_data", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
                .invokeExact(bytes, size) as MemorySegment).reinterpret(size.get(JAVA_LONG, 0))
        }
        if (data.byteSize() < stride.toLong() * h) return false
        val out = java.nio.ByteBuffer.allocateDirect(w * h * 4)
        val outSeg = MemorySegment.ofBuffer(out)
        if (stride == w * 4) {
            MemorySegment.copy(data, 0, outSeg, 0, w.toLong() * h * 4)
        } else {
            for (row in 0 until h) {
                MemorySegment.copy(data, row.toLong() * stride, outSeg, row.toLong() * w * 4, w.toLong() * 4)
            }
        }
        pendingShm.set(ShmFrame(w, h, out))
        return true
    }

    /** A frame is waiting to be taken, and with it the ack the page renders on. */
    val hasPendingFrame: Boolean
        get() = pendingBuffer.get() != null || pendingShm.get() != null

    /** Newest dmabuf frame for the consumer; caller must [releaseBufferAsync] it. */
    fun takeBuffer(): MemorySegment? =
        pendingBuffer.getAndSet(null)?.also { framesTaken++ }

    fun bufferWidth(buffer: MemorySegment): Int = hWidth.invokeExact(buffer) as Int

    fun bufferHeight(buffer: MemorySegment): Int = hHeight.invokeExact(buffer) as Int

    /**
     * EGLImage for [buffer], for the GL consumer. Owned by the buffer and
     * valid until it is released. It belongs to the headless display's own
     * EGLDisplay, so binding it in the window's context is cross-display.
     */
    fun eglImageOf(buffer: MemorySegment): MemorySegment =
        fn(webkit, "wpe_buffer_import_to_egl_image", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
            .invokeExact(buffer, MemorySegment.NULL) as MemorySegment

    /**
     * What a dmabuf describes. Single plane only, which is what WebKit hands
     * out on the presenting GPU -- [exportDmabuf] refuses anything else
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

    private var loggedPlanes = false

    /** [buffer] as a [Dmabuf], with its own duplicate of the fd. */
    fun exportDmabuf(buffer: MemorySegment): Dmabuf? {
        val planes = hPlanes.invokeExact(buffer) as Int
        if (planes != 1) {
            if (!loggedPlanes) {
                loggedPlanes = true
                System.err.println("[wpe] dmabuf has $planes planes; only 1 is handled")
            }
            return null
        }
        val fd = hDup.invokeExact(hFd.invokeExact(buffer, 0) as Int) as Int
        if (fd < 0) return null
        return Dmabuf(
            fd = fd,
            fourcc = hFormat.invokeExact(buffer) as Int,
            modifier = hModifier.invokeExact(buffer) as Long,
            stride = hStride.invokeExact(buffer, 0) as Int,
            offset = hOffset.invokeExact(buffer, 0) as Int,
            width = bufferWidth(buffer),
            height = bufferHeight(buffer),
        )
    }

    /** Hand a taken buffer back to WebKit. Safe from any thread. */
    fun releaseBufferAsync(buffer: MemorySegment) {
        Glib.post { release(buffer) }
    }

    /** Back to WebKit, dropping the reference [handleBuffer] took. */
    private fun release(buffer: MemorySegment) {
        released(buffer)
        unref(buffer)
    }

    private fun released(buffer: MemorySegment) {
        hReleased.invokeExact(view, buffer)
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
     * Acknowledge the current frame so WebKit renders the next one. The EDT
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
        val buffer = ackOwed.getAndSet(null) ?: return
        hRendered.invokeExact(view, buffer)
        unref(buffer)
    }

    // ---- input, forwarded from the host's GLFW callbacks ----
    // Pointer coordinates are logical. Modifiers are WPEModifiers: keyboard
    // bits 0-3, held buttons from 1<<8. Buttons are 1 left, 2 middle, 3 right.

    private val hEvent by lazy { fn(webkit, "wpe_view_event", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val hEventUnref by lazy { fn(webkit, "wpe_event_unref", FunctionDescriptor.ofVoid(ADDRESS)) }

    private fun now(): Int = (System.nanoTime() / 1_000_000L).toInt()

    private fun send(event: MemorySegment) {
        if (event.equals(MemorySegment.NULL)) return
        hEvent.invokeExact(view, event)
        hEventUnref.invokeExact(event)
    }

    private fun viewReady() = !view.equals(MemorySegment.NULL)

    fun dispatchPointer(motion: Boolean, x: Int, y: Int, button: Int, pressed: Boolean, modifiers: Int) {
        Glib.post {
            if (!viewReady()) return@post
            val time = now()
            val event = if (motion) {
                fn(webkit, "wpe_event_pointer_move_new", FunctionDescriptor.of(
                    ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                )).invokeExact(
                    EVENT_POINTER_MOVE, view, SOURCE_MOUSE, time, modifiers,
                    x.toDouble(), y.toDouble(), 0.0, 0.0,
                ) as MemorySegment
            } else {
                val pressCount = if (pressed) {
                    fn(webkit, "wpe_view_compute_press_count", FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, JAVA_INT,
                    )).invokeExact(view, x.toDouble(), y.toDouble(), button, time) as Int
                } else {
                    0
                }
                fn(webkit, "wpe_event_pointer_button_new", FunctionDescriptor.of(
                    ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT,
                )).invokeExact(
                    if (pressed) EVENT_POINTER_DOWN else EVENT_POINTER_UP, view, SOURCE_MOUSE,
                    time, modifiers, button, x.toDouble(), y.toDouble(), pressCount,
                ) as MemorySegment
            }
            send(event)
        }
    }

    /** Wheel notches, positive up and left, as GLFW reports them. */
    fun dispatchScroll(x: Int, y: Int, dx: Double, dy: Double) {
        Glib.post {
            if (!viewReady()) return@post
            val event = fn(webkit, "wpe_event_scroll_new", FunctionDescriptor.of(
                ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE,
            )).invokeExact(
                view, SOURCE_MOUSE, now(), 0, dx, dy, 0, 0, x.toDouble(), y.toDouble(),
            ) as MemorySegment
            send(event)
        }
    }

    /** [keysym] is the XKB keysym; [hardware] the XKB keycode (evdev + 8). */
    fun dispatchKey(keysym: Int, hardware: Int, pressed: Boolean, modifiers: Int) {
        Glib.post {
            if (!viewReady()) return@post
            val event = fn(webkit, "wpe_event_keyboard_new", FunctionDescriptor.of(
                ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
            )).invokeExact(
                if (pressed) EVENT_KEY_DOWN else EVENT_KEY_UP, view, SOURCE_KEYBOARD,
                now(), modifiers, hardware, keysym,
            ) as MemorySegment
            send(event)
        }
    }

    /** Logical size; WebKit rasters at size * [dispatchScale]. */
    fun dispatchSize(w: Int, h: Int) {
        Glib.post { if (viewReady()) resize(w, h) }
    }

    private fun resize(w: Int, h: Int) {
        fn(webkit, "wpe_toplevel_resize", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
            .invokeExact(toplevel, w, h) as Int
    }

    /**
     * Device pixels per CSS pixel. With this set the page keeps its logical
     * size and coordinates but rasters (and exports) at physical resolution
     * -- the difference between soft and crisp chrome on a scaled output.
     */
    fun dispatchScale(scale: Float) {
        Glib.post {
            if (!viewReady()) return@post
            fn(webkit, "wpe_toplevel_scale_changed", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE))
                .invokeExact(toplevel, scale.toDouble())
        }
    }

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

    companion object {
        /** offsetof(WPEViewClass, render_buffer): GObjectClass, then buffers_changed. */
        private const val RENDER_BUFFER_OFFSET = 144L

        private const val EVENT_POINTER_DOWN = 1
        private const val EVENT_POINTER_UP = 2
        private const val EVENT_POINTER_MOVE = 3
        private const val EVENT_KEY_DOWN = 7
        private const val EVENT_KEY_UP = 8
        private const val SOURCE_MOUSE = 0
        private const val SOURCE_KEYBOARD = 2

        /** Held-button modifier for WPE button [button] (1 left, 2 middle, 3 right). */
        fun buttonModifier(button: Int): Int = 1 shl (7 + button)

        /** The render node of the PCI device with these ids, if there is one. */
        fun renderNodeFor(vendorId: Int, deviceId: Int): String? {
            val nodes = java.io.File("/sys/class/drm").listFiles { f -> f.name.startsWith("renderD") }
                ?: return null
            fun id(f: java.io.File) = runCatching {
                Integer.decode(f.readText().trim())
            }.getOrNull()
            return nodes.sortedBy { it.name }.firstOrNull { node ->
                id(java.io.File(node, "device/vendor")) == vendorId &&
                    id(java.io.File(node, "device/device")) == deviceId
            }?.let { "/dev/dri/${it.name}" }
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

    // Always TRUE: on FALSE WebKit logs error->message without checking it,
    // and nothing here sets a GError.
    @JvmStatic
    fun renderBuffer(
        view: MemorySegment,
        buffer: MemorySegment,
        damage: MemorySegment,
        damageCount: Int,
        error: MemorySegment,
    ): Int {
        runCatching { owner?.handleBuffer(buffer) }.onFailure { it.printStackTrace() }
        return 1
    }
}
