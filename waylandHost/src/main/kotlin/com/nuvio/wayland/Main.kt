package com.nuvio.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable as androidxFocusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.MainUIDispatcher
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil.NULL
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.CanvasLayersComposeScene
import kotlin.system.exitProcess

private const val INITIAL_WIDTH = 1280
private const val INITIAL_HEIGHT = 800

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    GLFWErrorCallback.createPrint(System.err).set()

    // Ask for Wayland explicitly rather than letting GLFW autodetect, so that
    // falling back to XWayland is a visible failure instead of a silent one.
    if (System.getenv("WAYLAND_DISPLAY") != null)
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_WAYLAND)
    if (!glfwInit()) error("glfwInit failed")

    val platform = glfwGetPlatform()
    val platformName = when (platform) {
        GLFW_PLATFORM_WAYLAND -> "Wayland"
        GLFW_PLATFORM_X11 -> "X11"
        else -> "other($platform)"
    }
    println("GLFW platform: $platformName")

    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)

    val window = glfwCreateWindow(INITIAL_WIDTH, INITIAL_HEIGHT, "Nuvio Wayland host", NULL, NULL)
    if (window == NULL) {
        glfwTerminate()
        error("glfwCreateWindow failed")
    }

    // Compose's lifecycle insists on the AWT event queue being "the main
    // thread" (LifecycleRegistry enforces it), while GLFW insists on owning the
    // real main thread for event polling. Both can be satisfied: GLFW allows a
    // context to be current on any one thread, so the EDT owns the GL context
    // and does all rendering, and the main thread only polls and forwards input.
    //
    // Note this is AWT the event loop, not AWT the window system: no Canvas, no
    // JAWT, nothing that would drag us back onto X11.
    lateinit var context: DirectContext
    java.awt.EventQueue.invokeAndWait {
        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        GL.createCapabilities()
        println("GL_RENDERER: " + GL11.glGetString(GL11.GL_RENDERER))
        println("GL_VERSION:  " + GL11.glGetString(GL11.GL_VERSION))

        // Skiko binds to the context that is already current, so it never
        // touches GLX or X11 here. This is the whole reason the AWT-free path
        // works on Wayland while the AWT one cannot.
        context = DirectContext.makeGL()
    }

    // Optional video layer. mpv renders into the same GL context, underneath
    // Compose, via the render API -- no embedded window, no "wid", no
    // XComposite capture of an overlay. The api-type asks for the libplacebo
    // renderer, so this keeps vo=gpu-next quality rather than dropping to the
    // legacy one.
    val mediaUrl = System.getProperty("nuvio.wayland.media")
    val mpvPath = System.getProperty("nuvio.wayland.libmpv")
    val runRealAppEarly = System.getProperty("nuvio.wayland.realApp")?.toBoolean() ?: false
    var mpv: Mpv? = null
    var videoHost: WaylandVideoHost? = null
    if (mediaUrl != null || runRealAppEarly) {
        if (!Mpv.load(mpvPath)) {
            System.err.println("FAIL: could not load libmpv (nuvio.wayland.libmpv=$mpvPath)")
            exitProcess(1)
        }
        mpv = Mpv.create().apply {
            setOption("config", "no")
            setOption("terminal", "yes")
            setOption("msg-level", "all=info")
            if (!runRealAppEarly) setOption("audio", "no")
            setOption("vo", "libmpv")
            if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
                setOption("msg-level", "all=v")
            }
            // config=no above means the user's mpv.conf never loads, and mpv's
            // own default is hwdec=no -- so without this every stream is
            // software-decoded. "auto" picks nvdec with cuda-interop here,
            // which is the zero-copy path the render API was built for.
            setOption("hwdec", System.getProperty("nuvio.wayland.hwdec") ?: "auto")
            initialize()
            java.awt.EventQueue.invokeAndWait {
                createRenderContext(Mpv.MPV_RENDER_API_TYPE_OPENGL_NEXT) { name ->
                    glfwGetProcAddress(name)
                }
            }
            if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
                requestLogMessages("v")
            }
            if (mediaUrl != null) command("loadfile", mediaUrl)
        }
        println("mpv render context: ${Mpv.MPV_RENDER_API_TYPE_OPENGL_NEXT}")
        run {
            // Hand the app a video sink so its player surface stops reaching
            // for SwingPanel, which needs an AWT-backed scene we do not have.
            videoHost = WaylandVideoHost(mpv!!, context)
            if (runRealAppEarly) {
                com.nuvio.app.features.player.desktop.WaylandVideoBridge.delegate = videoHost
                println("video bridge: installed")
            }
            if (mediaUrl != null) videoHost!!.markLoaded()
        }
    }

    var width = INITIAL_WIDTH
    var height = INITIAL_HEIGHT
    var renderTarget: BackendRenderTarget? = null
    var surface: Surface? = null

    fun recreateSurface() {
        surface?.close()
        renderTarget?.close()
        renderTarget = BackendRenderTarget.makeGL(
            width, height, 0, 8,
            GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            FramebufferFormat.GR_GL_RGBA8,
        )
        surface = Surface.makeFromBackendRenderTarget(
            context, renderTarget!!, SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        ) ?: error("Surface.makeFromBackendRenderTarget returned null")
    }
    java.awt.EventQueue.invokeAndWait { recreateSurface() }

    var frames by mutableStateOf(0)
    val probePixels = System.getProperty("nuvio.wayland.probe")?.toBoolean() ?: false
    val videoLog = System.getProperty("nuvio.wayland.videoLog")?.toBoolean() ?: false

    // Density must be the output's real scale, not 1: Compose sizes everything
    // in dp, so density 1 on a fractionally scaled display lays the whole UI
    // out visibly smaller than the stock (AWT) build, which gets the system
    // scale from the toolkit. Framebuffer/window ratio is exactly that scale.
    fun currentScale(): Float {
        val ww = IntArray(1); val wh = IntArray(1)
        val fw = IntArray(1); val fh = IntArray(1)
        glfwGetWindowSize(window, ww, wh)
        glfwGetFramebufferSize(window, fw, fh)
        return if (ww[0] > 0) fw[0].toFloat() / ww[0] else 1f
    }

    val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(currentScale()),
        size = androidx.compose.ui.unit.IntSize(width, height),
        coroutineContext = MainUIDispatcher,
    )
    val runRealApp = System.getProperty("nuvio.wayland.realApp")?.toBoolean() ?: false
    // Drives the app's own player surface directly, so playback can be
    // exercised without clicking through the UI. Mirrors Main.kt's
    // smokePlayerUrl harness.
    val smokePlayerUrl = System.getProperty("nuvio.wayland.smokePlayer")
    java.awt.EventQueue.invokeAndWait {
    if (runRealApp) {
        // Same preamble Main.kt runs before showing its window. initGtkEarly is
        // deliberately not called: it pins GDK to X11 for the WebKitGTK control
        // overlay, which this host does not use.
        com.nuvio.app.features.profiles.ProfileRepository.loadCachedProfiles()
        // AppIconRepository is skipped: it only feeds the AWT window icon, and
        // GLFW sets ours.
        if (smokePlayerUrl != null) {
            scene.setContent {
                com.nuvio.app.core.ui.NuvioTheme {
                    com.nuvio.app.features.player.PlatformPlayerSurface(
                        sourceUrl = smokePlayerUrl,
                        modifier = Modifier.fillMaxSize(),
                        onControllerReady = {},
                        onSnapshot = {},
                        onError = { println("player error: $it") },
                    )
                }
            }
            println("content: player smoke harness")
        } else {
            scene.setContent { com.nuvio.app.App() }
            println("content: real Nuvio app")
        }
    } else {
        scene.setContent {
            // Echoes what Compose itself receives, which is the only proof that
            // input survives the whole GLFW -> InputRouter -> scene path. The
            // router's own counters stop at "we called sendPointerEvent".
            val focusRequester = androidx.compose.runtime.remember {
                androidx.compose.ui.focus.FocusRequester()
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
            }
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(focusRequester)
                    .androidxFocusable()
                    .onKeyEvent { e ->
                        println("[wayland-input] compose key: ${e.key} ${e.type}")
                        true
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                // Moves would flood; presses are the signal.
                                if (ev.type != androidx.compose.ui.input.pointer.PointerEventType.Move) {
                                    val at = ev.changes.firstOrNull()?.position
                                    println("[wayland-input] compose pointer: ${ev.type} at $at")
                                }
                            }
                        }
                    },
            ) {
                val vh = videoHost
                if (vh != null) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            vh.drawVideo(c.nativeCanvas, size.width, size.height)
                        }
                    }
                }
                Column(Modifier.padding(32.dp)) {
                    Text("Compose on Wayland, without AWT", color = Color(0xFFE0E0E0))
                    Text("GLFW platform: $platformName", color = Color(0xFF9AD29A))
                    Text("frames: $frames", color = Color(0xFF9AA0D2))
                }
            }
        }
        println("content: demo")
    }
    }

    val input = InputRouter(window, scene)
    input.install()

    // Keyboard needs an owner. On the AWT path the window grants Compose focus
    // when it gains it; with a bare scene nothing does, so every key event is
    // delivered and then dropped by the focus system. Mirror what a window
    // does: take focus now, and follow the compositor's focus from then on.
    java.awt.EventQueue.invokeLater {
        scene.focusManager.takeFocus(androidx.compose.ui.focus.FocusDirection.Next)
    }
    glfwSetWindowFocusCallback(window) { _, focused ->
        java.awt.EventQueue.invokeLater {
            if (focused) {
                scene.focusManager.takeFocus(androidx.compose.ui.focus.FocusDirection.Next)
            } else {
                scene.focusManager.releaseFocus()
            }
        }
    }

    var exitCode = 0
    val timings = FrameTimings()
    // The demo path is self-terminating so it can be run unattended; long
    // enough to produce several per-second timing reports.
    val demoFrames = System.getProperty("nuvio.wayland.demoFrames")?.toInt() ?: 120

    // Forces a repaint regardless of invalidation state: after a resize the
    // surface is new and holds nothing, so "nothing changed" would leave the
    // window blank.
    var forceRepaint = true

    /** Returns true if this iteration actually presented a frame. */
    fun renderOneFrame(): Boolean {

        val w = IntArray(1); val h = IntArray(1)
        glfwGetFramebufferSize(window, w, h)
        if (w[0] != width || h[0] != height) {
            width = w[0]; height = h[0]
            if (width > 0 && height > 0) {
                recreateSurface()
                forceRepaint = true
                scene.size = androidx.compose.ui.unit.IntSize(width, height)
                // Pointer positions arrive in window coordinates but the
                // scene works in framebuffer pixels; on a fractionally
                // scaled output those differ. The same ratio is the UI
                // density, which can change when the window moves outputs.
                val scale = currentScale()
                input.scale = scale
                if (scene.density.density != scale) scene.density = Density(scale)
            }
        }

        val s = surface ?: return false

        // Video goes into an offscreen texture, never into the window, so it
        // composites as ordinary Compose content rather than sitting under the
        // scene where any opaque background would cover it.
        var t = System.nanoTime()
        val videoChanged = videoHost?.renderFrame(width, height) ?: false
        timings.add("mpv", System.nanoTime() - t)

        if (videoLog) {
            mpv?.pumpEvents { println(it) }
            videoHost?.report(System.nanoTime())?.let { println("[wayland-video] $it") }
            input.report(System.nanoTime())?.let { println("[wayland-video] $it") }
            timings.report(System.nanoTime())?.let { println("[wayland-video] $it") }
        }

        // Repainting a scene that has not changed costs a full rasterization of
        // the whole tree -- measured at 28ms for the app's own UI, against
        // 1.4ms for trivial content -- and buys nothing. Present only when
        // there is something new: a video frame, a Compose invalidation, or a
        // surface that was just rebuilt.
        if (!forceRepaint && !videoChanged && !scene.hasInvalidations()) return false
        forceRepaint = false

        t = System.nanoTime()
        s.canvas.clear(0xFF101014.toInt())
        scene.render(s.canvas.asComposeCanvas(), System.nanoTime())
        timings.add("scene", System.nanoTime() - t)

        t = System.nanoTime()
        context.flush()
        timings.add("flush", System.nanoTime() - t)

        if (probePixels && frames % 15 == 0) {
            // Same pixel, now after Compose has drawn. If mpv's value was
            // non-black and this is black, Compose is painting over the video.
            // Hash a region rather than sample one pixel: a single centre
            // pixel is constant in plenty of content, so it cannot tell a live
            // frame from a frozen one -- which is exactly the bug it missed.
            val n = 64
            val buf = java.nio.ByteBuffer.allocateDirect(n * n * 4)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            GL11.glReadPixels(
                (width - n) / 2, (height - n) / 2, n, n,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf,
            )
            var hash = 0
            var nonBlack = 0
            for (i in 0 until n * n) {
                val r = buf.get(i * 4).toInt() and 0xFF
                val g = buf.get(i * 4 + 1).toInt() and 0xFF
                val b = buf.get(i * 4 + 2).toInt() and 0xFF
                hash = hash * 31 + (r shl 16 or (g shl 8) or b)
                if (r + g + b > 12) nonBlack++
            }
            println("probe: frame=$frames hash=$hash nonBlackPixels=$nonBlack/${n * n}")
        }
        t = System.nanoTime()
        glfwSwapBuffers(window)
        timings.add("swap", System.nanoTime() - t)

        timings.endFrame()

        frames++
        if (!runRealApp && frames == demoFrames) {
        println("RESULT: rendered $demoFrames frames on $platformName")
        if (platformName != "Wayland") {
            System.err.println("FAIL: not running on Wayland")
            exitCode = 1
        }
        glfwSetWindowShouldClose(window, true)
        }
        return true
    }

    var presented = true
    try {
        while (!glfwWindowShouldClose(window)) {
            // Input callbacks fire from here, on the main thread, and are
            // forwarded to the EDT by InputRouter.
            val beforePoll = System.nanoTime()
            // When the last iteration presented, the vsync-blocking swap paces
            // us. When it did not, there is nothing to block on, so wait for
            // input with a short timeout instead of spinning -- Compose's own
            // invalidations do not wake GLFW, hence the timeout rather than an
            // indefinite wait.
            if (presented) glfwPollEvents() else glfwWaitEventsTimeout(0.004)
            val afterPoll = System.nanoTime()
            timings.add("poll", afterPoll - beforePoll)
            java.awt.EventQueue.invokeAndWait {
                // How long the render task sat in the AWT queue behind Compose's
                // own work. This is the cost of the main/EDT split, measured
                // rather than assumed.
                timings.add("edtWait", System.nanoTime() - afterPoll)
                presented = renderOneFrame()
            }
        }
    } finally {
        mpv?.close()
        scene.close()
        surface?.close()
        renderTarget?.close()
        context.close()
        glfwDestroyWindow(window)
        glfwTerminate()
    }
    println("OK: clean shutdown")
    exitProcess(exitCode)
}
