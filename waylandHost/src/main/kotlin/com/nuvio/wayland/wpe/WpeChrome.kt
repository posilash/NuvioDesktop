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
 * WPE runs in pure-SHM mode (wpe_fdo_initialize_shm) with the web process
 * pinned to Mesa llvmpipe: it never touches the GPU (NVIDIA garbles its
 * GL->SHM alpha readback, and GPU work there contends with mpv). Exports are
 * copied out on the GLib thread and handed to the EDT as [ShmFrame]s; the
 * frame-complete ack fires when the EDT consumes one, so the page naturally
 * paces to the window's present rate.
 *
 * Threading: all WebKit calls happen on the GLib thread ([Glib.post]);
 * script messages arrive there and are forwarded verbatim to [onMessage].
 */
class WpeChrome(
    private val width: Int,
    private val height: Int,
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

    fun start(pageUri: String) {
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

        // Pure software WPE: the nested compositor offers only wl_shm and the
        // WEBKIT_DISABLE_* env keeps the web process off GL entirely. This is
        // EXACTLY what upstream's linux branch ships -- its player_bridge.cpp
        // documents the same NVIDIA failure ("DMABUF renderer yields controls
        // snapshots with degraded alpha ... fully opaque") and forces the
        // software path for the same reason. The cost is controlled by the
        // host's activity gate, not here: "normal watching pays nothing".
        fn(fdo, "wpe_fdo_initialize_shm", FunctionDescriptor.of(JAVA_BOOLEAN))
            .invokeExact() as Boolean

        // struct wpe_view_backend_exportable_fdo_egl_client:
        //   [0] export_egl_image (legacy)  [1] export_fdo_egl_image  [2] shm  [3,4] reserved
        val client = arena.allocate(MemoryLayout.sequenceLayout(5, ADDRESS))
        val shmStub = linker.upcallStub(
            MethodHandles.lookup().findStatic(
                WpeCallbacks::class.java, "exportShmBuffer",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java),
            ),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), arena,
        )
        WpeCallbacks.owner = this
        client.setAtIndex(ADDRESS, 0, MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 1, MemorySegment.NULL)
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
        if (System.getProperty("nuvio.wayland.chromeSoftware")?.toBoolean() == true) run {
            val setenv = linker.downcallHandle(
                linker.defaultLookup().find("setenv").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
            )
            fun env(k: String, v: String) {
                Arena.ofConfined().use { a ->
                    setenv.invokeExact(a.allocateFrom(k), a.allocateFrom(v), 1) as Int
                    Unit
                }
            }
            env("LIBGL_ALWAYS_SOFTWARE", "1")
            env("GALLIUM_DRIVER", "llvmpipe")
            env("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/50_mesa.json")
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

    // ---- GLib-thread callbacks ----

    private val wlServer by lazy { SymbolLookup.libraryLookup("libwayland-server.so.0", arena) }

    /** A copied chrome frame: tightly packed BGRA (wl ARGB8888) pixels. */
    class ShmFrame(val width: Int, val height: Int, val pixels: java.nio.ByteBuffer)

    private val pendingShm = java.util.concurrent.atomic.AtomicReference<ShmFrame?>(null)

    // Ack conservation: dispatch_frame_complete is only ever valid as the
    // answer to one export. Un-paired acks sent WPE into a render storm --
    // one logged burst re-rendered the page 133k times in seconds.
    private val ackOwed = java.util.concurrent.atomic.AtomicBoolean(false)

    internal fun handleShmExport(buffer: MemorySegment) {
        framesExported++
        ackOwed.set(true)
        var handedOff = false
        runCatching {
            if (!visible) return@runCatching // skip the copy entirely
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
            // The ack comes when the EDT consumes the frame: the page paces
            // itself to the window's actual present rate.
            org.lwjgl.glfw.GLFW.glfwPostEmptyEvent()
        } else {
            // Hidden or inactive: a slow trickle, never instant -- an
            // instant ack re-ran the renderer flat out (~90fps of CPU
            // raster). 5fps keeps the page's rAF-driven logic alive so it
            // can still react (reveal chrome, run its fade) at ~zero cost.
            Glib.postDelayed(200) { dispatchFrameComplete() }
        }
    }

    /** Newest chrome frame for the EDT; caller must [ackFrame] after use. */
    fun takeShmFrame(): ShmFrame? = pendingShm.getAndSet(null)


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
        fn(fdo, "wpe_view_backend_exportable_fdo_dispatch_frame_complete",
            FunctionDescriptor.ofVoid(ADDRESS))
            .invokeExact(exportable)
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
}
