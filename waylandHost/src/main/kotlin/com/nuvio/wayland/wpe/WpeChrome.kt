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
import java.util.concurrent.atomic.AtomicReference

/**
 * Upstream's desktop player chrome (player-ui/controls.html), hosted the way
 * Stremio hosts its web UI: an offscreen web engine composited as a layer in
 * the same window and GPU scene as the mpv render-API video. Stremio uses
 * QtWebEngine for the offscreen half; the native-Wayland equivalent is WPE
 * WebKit, whose fdo backend exports each rendered frame as an EGLImage.
 *
 * Threading: all WebKit calls happen on the GLib thread ([Glib.post]).
 * Exported frames are handed off through [takeImage] to whichever GL thread
 * composites; script messages arrive on the GLib thread and are forwarded
 * verbatim to [onMessage].
 *
 * Every signature below is taken from the installed headers, not memory --
 * including the detail that export_fdo_egl_image is slot ONE of the client
 * struct (slot zero is the deprecated raw-EGLImage variant; filling the wrong
 * slot yields a silent black view).
 */
class WpeChrome(
    private val eglDisplay: Long,
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

    private var exportable: MemorySegment = MemorySegment.NULL
    private var webView: MemorySegment = MemorySegment.NULL

    /** Latest exported frame awaiting import; consumer takes, then [frameDone]. */
    private val pendingImage = AtomicReference<MemorySegment?>(null)

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

        val ok = fn(fdo, "wpe_fdo_initialize_for_egl_display",
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS))
            .invokeExact(MemorySegment.ofAddress(eglDisplay)) as Boolean
        check(ok) { "wpe_fdo_initialize_for_egl_display failed" }

        // struct wpe_view_backend_exportable_fdo_egl_client:
        //   [0] export_egl_image (legacy)  [1] export_fdo_egl_image  [2] shm  [3,4] reserved
        val client = arena.allocate(MemoryLayout.sequenceLayout(5, ADDRESS))
        val exportStub = linker.upcallStub(
            MethodHandles.lookup().findStatic(
                WpeCallbacks::class.java, "exportImage",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java),
            ),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), arena,
        )
        WpeCallbacks.owner = this
        client.setAtIndex(ADDRESS, 0, MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 1, exportStub)
        client.setAtIndex(ADDRESS, 2, MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 3, MemorySegment.NULL)
        client.setAtIndex(ADDRESS, 4, MemorySegment.NULL)

        exportable = fn(fdo, "wpe_view_backend_exportable_fdo_egl_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT))
            .invokeExact(client, MemorySegment.NULL, width, height) as MemorySegment
        viewBackend = fn(fdo, "wpe_view_backend_exportable_fdo_get_view_backend",
            FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(exportable) as MemorySegment

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

        fn(webkit, "webkit_web_view_load_uri", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
            .invokeExact(webView, arena.allocateFrom(pageUri))
        println("[wpe] view up, loading $pageUri")
    }

    // ---- GLib-thread callbacks ----

    internal fun handleExport(image: MemorySegment) {
        framesExported++
        // Newest wins; an unconsumed predecessor goes straight back to WPE.
        pendingImage.getAndSet(image)?.let { stale -> releaseImage(stale) }
    }

    internal fun handleMessage(jscValue: MemorySegment) {
        val json = fn(webkit, "jsc_value_to_json",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
            .invokeExact(jscValue, 0) as MemorySegment
        if (!json.equals(MemorySegment.NULL)) {
            onMessage(json.reinterpret(Long.MAX_VALUE).getString(0))
        }
    }

    // ---- consumer side ----

    /** Newest exported frame, or null. Caller imports, then calls [frameDone]. */
    fun takeImage(): MemorySegment? = pendingImage.getAndSet(null)

    /** EGLImageKHR handle inside [image]; valid until the image is released. */
    fun eglImageOf(image: MemorySegment): MemorySegment =
        fn(fdo, "wpe_fdo_egl_exported_image_get_egl_image",
            FunctionDescriptor.of(ADDRESS, ADDRESS))
            .invokeExact(image) as MemorySegment

    /**
     * Acknowledge the current frame so WPE renders the next one. Distinct
     * from releasing: complete means "send more", release means "this buffer
     * is free". Conflating them deadlocks after the first frame -- WPE waits
     * for the ack, the consumer waits for a successor that can never come.
     */
    fun ackFrame() {
        Glib.post { dispatchFrameComplete() }
    }

    fun frameDone(image: MemorySegment) {
        Glib.post { releaseImage(image) }
    }

    private fun releaseImage(image: MemorySegment) {
        fn(fdo, "wpe_view_backend_exportable_fdo_egl_dispatch_release_exported_image",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
            .invokeExact(exportable, image)
    }

    private fun dispatchFrameComplete() {
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
            }
        }
    }
}

/**
 * Consumer half: turns exported EGLImages into a GL texture on the caller's
 * context and keeps the image alive exactly as long as the texture samples
 * it (an EGLImage released back to WPE while still bound is undefined).
 */
class WpeChromeLayer(private val chrome: WpeChrome) {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofShared()
    private val egl = SymbolLookup.libraryLookup("libEGL.so.1", arena)

    // glEGLImageTargetTexture2DOES is an extension entry point; per EGL spec
    // it must come from eglGetProcAddress, not dlsym.
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

    var texture = 0
        private set
    var imageWidth = 0
        private set
    var imageHeight = 0
        private set
    private var displayedImage: MemorySegment? = null
    @Volatile var framesImported = 0L
        private set

    private val imageWidthFn by lazy {
        linker.downcallHandle(
            SymbolLookup.libraryLookup("libWPEBackend-fdo-1.0.so.1", arena)
                .find("wpe_fdo_egl_exported_image_get_width").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
        )
    }
    private val imageHeightFn by lazy {
        linker.downcallHandle(
            SymbolLookup.libraryLookup("libWPEBackend-fdo-1.0.so.1", arena)
                .find("wpe_fdo_egl_exported_image_get_height").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
        )
    }

    /**
     * Import the newest exported frame into [texture], on the current GL
     * context. Returns true when the texture changed. The previously
     * displayed image is only released once its successor is bound.
     */
    fun importLatest(): Boolean {
        val image = chrome.takeImage() ?: return false
        if (texture == 0) {
            texture = org.lwjgl.opengl.GL11.glGenTextures()
        }
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture)
        imageTargetTexture.invokeExact(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, chrome.eglImageOf(image),
        )
        org.lwjgl.opengl.GL11.glTexParameteri(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR,
        )
        org.lwjgl.opengl.GL11.glTexParameteri(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR,
        )
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0)
        imageWidth = imageWidthFn.invokeExact(image) as Int
        imageHeight = imageHeightFn.invokeExact(image) as Int
        displayedImage?.let { chrome.frameDone(it) }
        displayedImage = image
        framesImported++
        chrome.ackFrame()
        return true
    }
}

/** FFM upcalls must target static methods. */
internal object WpeCallbacks {
    @JvmStatic
    var owner: WpeChrome? = null

    @JvmStatic
    fun exportImage(data: MemorySegment, image: MemorySegment) {
        owner?.handleExport(image)
    }

    @JvmStatic
    fun playerMessage(ucm: MemorySegment, jscValue: MemorySegment, data: MemorySegment) {
        owner?.handleMessage(jscValue)
    }
}
