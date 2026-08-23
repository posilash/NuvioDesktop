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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

private var chromeWrapper: Triple<Int, org.jetbrains.skia.BackendRenderTarget, org.jetbrains.skia.Surface>? = null

/**
 * Draw the WPE chrome texture over everything else. Wrapped the same way the
 * video textures are (an FBO of ours around the shared texture, a Skia
 * surface around that); WPE renders premultiplied RGBA, top-row-first.
 */
private fun drawChromeTexture(
    wrapper: Triple<Int, org.jetbrains.skia.BackendRenderTarget, org.jetbrains.skia.Surface>?,
    layer: com.nuvio.wayland.wpe.WpeChromeLayer,
    canvas: org.jetbrains.skia.Canvas,
    context: org.jetbrains.skia.DirectContext,
    width: Int,
    height: Int,
): Triple<Int, org.jetbrains.skia.BackendRenderTarget, org.jetbrains.skia.Surface> {
    var w = wrapper
    if (w == null) {
        val fbo = org.lwjgl.opengl.GL30.glGenFramebuffers()
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbo)
        org.lwjgl.opengl.GL30.glFramebufferTexture2D(
            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, layer.texture, 0,
        )
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0)
        val rt = org.jetbrains.skia.BackendRenderTarget.makeGL(
            INITIAL_WIDTH, INITIAL_HEIGHT, 0, 8, fbo,
            org.jetbrains.skia.FramebufferFormat.GR_GL_RGBA8,
        )
        val surface = org.jetbrains.skia.Surface.makeFromBackendRenderTarget(
            context, rt, org.jetbrains.skia.SurfaceOrigin.TOP_LEFT,
            org.jetbrains.skia.SurfaceColorFormat.RGBA_8888, org.jetbrains.skia.ColorSpace.sRGB,
        ) ?: error("could not wrap chrome texture")
        w = Triple(fbo, rt, surface)
    }
    w.third.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
    context.resetGL(org.jetbrains.skia.GLBackendState.TEXTURE_BINDING)
    val snapshot = w.third.makeImageSnapshot()
    canvas.drawImageRect(
        snapshot,
        org.jetbrains.skia.Rect.makeWH(INITIAL_WIDTH.toFloat(), INITIAL_HEIGHT.toFloat()),
        org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()),
        org.jetbrains.skia.SamplingMode.LINEAR, null, true,
    )
    snapshot.close()
    return w
}

private const val INITIAL_WIDTH = 1280
private const val INITIAL_HEIGHT = 800

/** The user's real mpv.conf: $XDG_CONFIG_HOME/mpv, ~/.config/mpv, ~/.mpv. */
private fun userMpvConfPath(): String? {
    val candidates = buildList {
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
            ?.let { add("$it/mpv/mpv.conf") }
        System.getenv("HOME")?.let {
            add("$it/.config/mpv/mpv.conf")
            add("$it/.mpv/mpv.conf")
        }
    }
    return candidates.firstOrNull { java.io.File(it).isFile }
}

/** Resident set size in MB, for the SIGKILL investigation: evidence, not theory. */
private fun rssMb(): Long = runCatching {
    java.io.File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024)
    } ?: -1
}.getOrDefault(-1)

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

    // Second, invisible window whose context shares objects with the first:
    // the video thread's home. Created here, before any context goes current
    // on another thread -- GLFW requires the share context not be current
    // elsewhere during creation. Textures made in one are usable in the other;
    // that sharing is what makes the video path zero-copy end to end.
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    val videoWindow = glfwCreateWindow(64, 64, "nuvio-video", NULL, window)
    if (videoWindow == NULL) {
        glfwTerminate()
        error("glfwCreateWindow (video context) failed")
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

    // Optional video layer. mpv renders on its own thread, into textures the
    // window context shares, via the render API -- no embedded window, no
    // "wid", no XComposite capture of an overlay. The api-type asks for the
    // libplacebo renderer, so this keeps vo=gpu-next quality rather than
    // dropping to the legacy one.
    val mediaUrl = System.getProperty("nuvio.wayland.media")
    val mpvPath = System.getProperty("nuvio.wayland.libmpv")
    val runRealAppEarly = System.getProperty("nuvio.wayland.realApp")?.toBoolean() ?: false
    var mpv: Mpv? = null
    var pipeline: VideoPipeline? = null
    var videoHost: WaylandVideoHost? = null
    val videoFrameReady = java.util.concurrent.atomic.AtomicBoolean(false)
    if (mediaUrl != null || runRealAppEarly) {
        if (!Mpv.load(mpvPath)) {
            System.err.println("FAIL: could not load libmpv (nuvio.wayland.libmpv=$mpvPath)")
            exitProcess(1)
        }
        mpv = Mpv.create().apply {
            // NuvioLinux's config scheme, adopted because it is the better
            // one: parse the user's ACTUAL mpv.conf explicitly with
            // mpv_load_config_file -- at a moment we choose -- then apply the
            // embedding invariants after it, so they always win. No config=yes
            // trap (that file is parsed at initialize() and silently
            // overwrites earlier API sets), no separate app-specific conf.
            setOption("config", "no")
            setOption("load-scripts", "no")
            setOption("terminal", "yes")
            setOption("msg-level", "all=info")
            // Defaults the user's conf may override:
            setOption("hwdec", "auto")
            if (!runRealAppEarly) setOption("audio", "no")
            if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
                setOption("msg-level", "all=v")
            }
            userMpvConfPath()?.let {
                val ok = loadConfigFile(it)
                println("mpv config: $it ${if (ok) "loaded" else "FAILED"}")
            }
            // Embedding invariants, applied after the user config so no
            // config can break the player (same set NuvioLinux enforces):
            // vo=libmpv (a user vo opens mpv's own window), force-window=no,
            // idle=yes (a conf-driven core quit would strand the session),
            // and no watch-later (the app manages resume itself).
            setOption("vo", "libmpv")
            setOption("force-window", "no")
            setOption("idle", "yes")
            setOption("save-position-on-quit", "no")
            System.getProperty("nuvio.wayland.hwdec")?.let { setOption("hwdec", it) }
            initialize()
            // Event loop owns the queue; observed properties feed the state
            // cache. Log forwarding rides the same thread under videoLog.
            for (prop in WaylandVideoHost.OBSERVED_PROPERTIES) observeProperty(prop)
            startEventLoop(
                if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
                    { line: String -> println(line) }
                } else {
                    null
                },
            )
            if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
                requestLogMessages("v")
            }
        }
        pipeline = VideoPipeline(mpv!!, videoWindow).apply {
            onFrame = { videoFrameReady.set(true) }
            probe = System.getProperty("nuvio.wayland.probe")?.toBoolean() ?: false
            start()
        }
        // The render context is created on the pipeline's thread; loading a
        // file before it exists makes video-output init fail.
        pipeline!!.awaitReady()
        System.getProperty("nuvio.wayland.mpvExtra")?.split(';')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2)
            mpv!!.setProperty(k, v)
            println("mpvExtra: $k=$v")
        }
        if (mediaUrl != null) mpv!!.command("loadfile", mediaUrl)
        println("mpv render context: ${Mpv.MPV_RENDER_API_TYPE_OPENGL_NEXT} (video thread)")
        // Hand the app a video sink so its player surface stops reaching
        // for SwingPanel, which needs an AWT-backed scene we do not have.
        videoHost = WaylandVideoHost(mpv!!, pipeline!!, context)
        if (runRealAppEarly) {
            com.nuvio.app.features.player.desktop.WaylandVideoBridge.delegate = videoHost
            println("video bridge: installed")
        }
        if (mediaUrl != null) videoHost!!.markLoaded()
    }

    var width = INITIAL_WIDTH
    var height = INITIAL_HEIGHT
    var renderTarget: BackendRenderTarget? = null
    var surface: Surface? = null
    // The UI's own layer. The scene rasterizes here, only when it has
    // something new -- never on account of a video frame. Presenting is then
    // two cheap draws (video texture, UI texture) regardless of how expensive
    // the scene is, which is what keeps a 24fps stream smooth under an 18ms
    // UI. Transparent where the player surface punched its hole.
    var uiSurface: Surface? = null

    fun recreateSurface() {
        surface?.close()
        renderTarget?.close()
        uiSurface?.close()
        renderTarget = BackendRenderTarget.makeGL(
            width, height, 0, 8,
            GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            FramebufferFormat.GR_GL_RGBA8,
        )
        surface = Surface.makeFromBackendRenderTarget(
            context, renderTarget!!, SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        ) ?: error("Surface.makeFromBackendRenderTarget returned null")
        uiSurface = Surface.makeRenderTarget(
            context, false,
            org.jetbrains.skia.ImageInfo.makeN32Premul(width, height),
        ) ?: error("Surface.makeRenderTarget (UI layer) returned null")
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
        val cx = FloatArray(1); val cy = FloatArray(1)
        glfwGetWindowSize(window, ww, wh)
        glfwGetFramebufferSize(window, fw, fh)
        glfwGetWindowContentScale(window, cx, cy)
        val scale = if (ww[0] > 0) fw[0].toFloat() / ww[0] else 1f
        // Optional override for comparing UI sizing: the stock build runs
        // XWayland at density 1 regardless of the output's real scale, so its
        // UI is smaller than any native-scale app. -Pnuvio.wayland.uiScale=1.0
        // reproduces that look; unset means the display's true scale.
        val override = System.getProperty("nuvio.wayland.uiScale")?.toFloatOrNull()
        println(
            "[wayland-scale] window=${ww[0]}x${wh[0]} fb=${fw[0]}x${fh[0]} " +
                "contentScale=${cx[0]} -> density=${override ?: scale}" +
                if (override != null) " (override)" else "",
        )
        return override ?: scale
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
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxSize()
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                vh.setVideoRect(b.left, b.top, b.width, b.height)
                            },
                    ) {
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

    // The app's fullscreen button resolves through DesktopAppFullscreen, whose
    // stock handler drives an AWT window that does not exist here. GLFW's
    // window-monitor calls belong to the main thread, and the app invokes the
    // toggle from the EDT, so the handler blocks briefly on a main-thread
    // hop -- the main loop wakes on the posted event and applies it.
    val fullscreenApplied = java.util.concurrent.atomic.AtomicBoolean(false)
    val fullscreenRequests = java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.CountDownLatch>()
    var windowedX = 0; var windowedY = 0
    var windowedW = INITIAL_WIDTH; var windowedH = INITIAL_HEIGHT
    if (runRealAppEarly) {
        com.nuvio.app.features.player.desktop.WaylandVideoBridge.registerFullscreenToggle(
            handler = {
                val latch = java.util.concurrent.CountDownLatch(1)
                fullscreenRequests.add(latch)
                glfwPostEmptyEvent()
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            },
            isFullscreen = { fullscreenApplied.get() },
        )
    }
    fun applyFullscreenRequests() {
        while (true) {
            val latch = fullscreenRequests.poll() ?: return
            if (!fullscreenApplied.get()) {
                val x = IntArray(1); val y = IntArray(1)
                val w = IntArray(1); val h = IntArray(1)
                glfwGetWindowPos(window, x, y)
                glfwGetWindowSize(window, w, h)
                windowedX = x[0]; windowedY = y[0]; windowedW = w[0]; windowedH = h[0]
                val monitor = glfwGetPrimaryMonitor()
                val mode = glfwGetVideoMode(monitor)
                if (mode != null) {
                    glfwSetWindowMonitor(
                        window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate(),
                    )
                    fullscreenApplied.set(true)
                }
            } else {
                glfwSetWindowMonitor(
                    window, NULL, windowedX, windowedY, windowedW, windowedH, 0,
                )
                fullscreenApplied.set(false)
            }
            latch.countDown()
        }
    }

    // Milestone harness for the web chrome: bring WPE up against the real
    // controls page and count exported frames. Compositing and input arrive
    // in later stages; this proves the engine half.
    var wpeChrome: com.nuvio.wayland.wpe.WpeChrome? = null
    var wpeLayer: com.nuvio.wayland.wpe.WpeChromeLayer? = null
    if (System.getProperty("nuvio.wayland.webChrome")?.toBoolean() == true) {
        // Resolve the page against the repo root regardless of working dir.
        val page = sequenceOf(
            java.io.File("composeApp/src/desktopMain/resources/player-ui/controls.html"),
            java.io.File("../composeApp/src/desktopMain/resources/player-ui/controls.html"),
        ).map { it.absoluteFile.normalize() }.firstOrNull { it.isFile }
            ?: error("controls.html not found from ${java.io.File(".").absolutePath}")
        wpeChrome = com.nuvio.wayland.wpe.WpeChrome(
            eglDisplay = org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLDisplay(),
            width = INITIAL_WIDTH,
            height = INITIAL_HEIGHT,
            onMessage = { println("[wpe] message: $it") },
        ).also { it.start("file://" + page.path) }
        wpeLayer = com.nuvio.wayland.wpe.WpeChromeLayer(wpeChrome!!)
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

    // Scheduling state for scene-vs-video contention: the scene may only
    // start a rasterization when it can finish before the next video frame is
    // due. Costs and cadence are measured, not assumed.
    var sceneCostEmaMs = 8.0
    var videoIntervalEmaMs = 41.7
    var lastSceneRenderNs = System.nanoTime()
    // Cadence evidence for judder: how far apart video presents actually land.
    // A 24fps source on a 165Hz panel should alternate cleanly between 7- and
    // 6-vsync intervals (42.4/36.4ms); anything outside that pattern is
    // latency of ours, not arithmetic.
    var lastVideoPresentNs = 0L
    val cadenceBuckets = IntArray(12)
    var cadenceReportNs = 0L
    fun noteVideoPresent(now: Long) {
        if (lastVideoPresentNs != 0L) {
            val ms = (now - lastVideoPresentNs) / 1e6
            val bucket = (ms / 6.06).toInt().coerceIn(0, cadenceBuckets.size - 1)
            cadenceBuckets[bucket]++
            // Learn the source cadence from sane intervals only; pauses and
            // seeks would poison the average.
            if (ms in 15.0..100.0) {
                videoIntervalEmaMs += (ms - videoIntervalEmaMs) * 0.1
            }
        }
        lastVideoPresentNs = now
        if (videoLog && now - cadenceReportNs > 5_000_000_000L) {
            if (cadenceReportNs != 0L) {
                val body = cadenceBuckets.withIndex()
                    .filter { it.value > 0 }
                    .joinToString(" ") { "${it.index}v=${it.value}" }
                println("[wayland-video] cadence(vsyncs): $body")
                cadenceBuckets.fill(0)
            }
            cadenceReportNs = now
        }
    }
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
        val ui = uiSurface ?: return false

        if (videoLog) {
            wpeChrome?.let { chrome ->
                if (frames % 120 == 0) {
                    println("[wpe] exported=${chrome.framesExported} imported=${wpeLayer?.framesImported} err=${chrome.lastError}")
                }
            }
            videoHost?.report(System.nanoTime())?.let {
                println("[wayland-video] $it rss=${rssMb()}MB heap=${Runtime.getRuntime().let { r -> (r.totalMemory() - r.freeMemory()) / 1_048_576 }}MB")
            }
            input.report(System.nanoTime())?.let { println("[wayland-video] $it") }
            timings.report(System.nanoTime())?.let { println("[wayland-video] $it") }
        }

        // Present when there is something new: a video frame the pipeline
        // published, a Compose invalidation, or a surface that was just
        // rebuilt. Video presents take absolute priority: they reuse the last
        // UI layer as-is and never wait on a scene rasterization. The cadence
        // histogram showed why -- fullscreen scenes spike to 50ms, and video
        // frames that queue behind them clump into 1-2 vsync bursts followed
        // by 11-vsync gaps, which is exactly the judder the eye catches. The
        // scene gets rasterized in the gaps between video frames instead; at
        // 24fps there are 40ms of them, and when the UI is heavier than that
        // it is the chrome that degrades, never the video.
        val videoChanged = videoFrameReady.getAndSet(false)
        val chromePending = wpeChrome?.let { it.framesExported > (wpeLayer?.framesImported ?: 0L) } == true
        val sceneDirty = forceRepaint || scene.hasInvalidations()
        if (!videoChanged && !sceneDirty && !chromePending) return false

        // A scene rasterization is uninterruptible once started; at fullscreen
        // it can take 20-50ms, and a video frame arriving mid-raster queues
        // behind it -- the measured 11-vsync gaps. So the scene only starts
        // when it can finish before the next frame is due. When the UI is
        // heavier than the gap between frames, chrome updates degrade and the
        // video cadence stays intact, which is the right way round.
        var t = System.nanoTime()
        if (sceneDirty && !videoChanged) {
            val videoLive = videoHost?.hasFile == true &&
                lastVideoPresentNs != 0L && (t - lastVideoPresentNs) < 500_000_000L
            // When the scene costs more than a whole frame interval the defer
            // condition would hold forever and the UI would simply stop --
            // observed as "the player ui disappeared". Starvation gets a hard
            // bound: past it the scene renders even at the price of one late
            // video frame.
            val starvedMs = (t - lastSceneRenderNs) / 1e6
            if (videoLive && starvedMs < 120.0) {
                val untilNextFrameMs =
                    (lastVideoPresentNs - t) / 1e6 + (pipeline?.publishIntervalMs ?: videoIntervalEmaMs)
                if (untilNextFrameMs < sceneCostEmaMs * 1.2 + 3.0) {
                    // Too close: skip this iteration; the loop re-checks in
                    // ~4ms and the scene runs right after the frame instead.
                    return false
                }
            }
            forceRepaint = false
            lastSceneRenderNs = t
            ui.canvas.clear(0x00000000)
            scene.render(ui.canvas.asComposeCanvas(), System.nanoTime())
            val costMs = (System.nanoTime() - t) / 1e6
            sceneCostEmaMs += (costMs - sceneCostEmaMs) * 0.2
            timings.add("scene", System.nanoTime() - t)
        }

        // Chrome frames import on this thread (the GL context owner); a new
        // frame is a present reason of its own.
        val chromeChanged = java.awt.EventQueue.isDispatchThread() &&
            wpeLayer?.importLatest() == true

        t = System.nanoTime()
        s.canvas.clear(0xFF101014.toInt())
        videoHost?.compositeVideo(s.canvas)
        // Surface.draw, not makeImageSnapshot: a snapshot makes the next
        // scene render pay a full-resolution copy-on-write of the UI texture
        // (14MB at 2560x1440), which is what inflated scene costs to tens of
        // milliseconds and dragged the whole chrome down.
        ui.draw(s.canvas, 0, 0, null)
        wpeLayer?.let { layer ->
            if (layer.texture != 0) {
                chromeWrapper = drawChromeTexture(chromeWrapper, layer, s.canvas, context, width, height)
            }
        }
        timings.add("composite", System.nanoTime() - t)

        t = System.nanoTime()
        context.flush()
        timings.add("flush", System.nanoTime() - t)

        // Throwaway subtitle harness: white text in the bottom third means
        // subs render; zero bright pixels means they are lost in the pipeline.
        if (System.getProperty("nuvio.wayland.subTest")?.toBoolean() == true && frames % 60 == 30) {
            // Whole-frame scan: distinguishes "not rendered" from "rendered in
            // the wrong place". Reports the y-range of near-white pixels.
            val buf = java.nio.ByteBuffer.allocateDirect(width * height * 4)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
            var bright = 0; var minY = -1; var maxY = -1; var minX = -1; var maxX = -1
            for (y in 0 until height step 2) {
                for (x in 0 until width step 2) {
                    val i = (y * width + x) * 4
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    if (r > 230 && g > 230 && b > 230) {
                        bright++
                        if (minY < 0) minY = y
                        maxY = y
                        if (minX < 0 || x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
            }
            println("[sub-test] frame=$frames bright=$bright x=[$minX..$maxX] yGL=[$minY..$maxY] (win ${width}x$height)")
        }
        // Throwaway resize-mode harness: cycle Fit/Stretch/Zoom and read the
        // pillarbox edge; the bar is black under Fit, content under Stretch.
        if (System.getProperty("nuvio.wayland.resizeTest")?.toBoolean() == true) {
            when (frames) {
                150 -> { println("[resize-test] -> Stretch"); videoHost?.setResizeMode(com.nuvio.app.features.player.PlayerResizeMode.Stretch) }
                300 -> { println("[resize-test] -> Zoom"); videoHost?.setResizeMode(com.nuvio.app.features.player.PlayerResizeMode.Zoom) }
                450 -> { println("[resize-test] -> Fit"); videoHost?.setResizeMode(com.nuvio.app.features.player.PlayerResizeMode.Fit) }
            }
            if (frames % 30 == 0) {
                val px = java.nio.ByteBuffer.allocateDirect(4)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                GL11.glReadPixels(8, height / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px)
                val v = (px.get(0).toInt() and 0xFF) + (px.get(1).toInt() and 0xFF) + (px.get(2).toInt() and 0xFF)
                println("[resize-test] frame=$frames edgeSum=$v (${if (v > 30) "CONTENT" else "bar"})")
            }
        }
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
        if (videoChanged) {
            noteVideoPresent(System.nanoTime())
            // Vsync feedback: with ADVANCED_CONTROL, reported swaps let mpv
            // align frame target times to the display's actual cadence --
            // what a real VO gets from its swapchain.
            mpv?.reportSwap()
            // The safest moment to rasterize the scene is right here, just
            // after a frame was presented: the full frame interval lies
            // ahead. Deferring it to the next loop iteration burned 4-12ms
            // of that margin and let 20ms scenes collide with the next
            // frame -- the residual 11-vsync gaps.
            if (scene.hasInvalidations() &&
                sceneCostEmaMs < (pipeline?.publishIntervalMs ?: 41.7) - 8.0
            ) {
                val ts = System.nanoTime()
                forceRepaint = false
                ui.canvas.clear(0x00000000)
                scene.render(ui.canvas.asComposeCanvas(), System.nanoTime())
                context.flush()
                val costMs = (System.nanoTime() - ts) / 1e6
                sceneCostEmaMs += (costMs - sceneCostEmaMs) * 0.2
                lastSceneRenderNs = ts
                timings.add("scene", System.nanoTime() - ts)
            }
        }

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
            applyFullscreenRequests()
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
        // Teardown runs on a worker so the MAIN thread can keep answering the
        // compositor for its whole duration -- scene.close() alone can take
        // seconds for the full app tree, and a client that goes quiet after a
        // close request gets flagged unresponsive and SIGKILLed ("crash on
        // close", exit 137). Ordering within the worker still matters: scene
        // first (its disposals stop playback through a live mpv), then quit
        // and await the core's shutdown event, then the render context on its
        // owning thread, then the handle. GL cleanup happens on the EDT,
        // which owns that context; the windows die last, on this thread,
        // which owns GLFW.
        glfwHideWindow(window)
        val teardownDone = java.util.concurrent.CountDownLatch(1)
        Thread({
            runCatching {
                java.awt.EventQueue.invokeAndWait { scene.close() }
                mpv?.quitAndAwaitShutdown()
                pipeline?.stop()
                mpv?.close()
                java.awt.EventQueue.invokeAndWait {
                    surface?.close()
                    renderTarget?.close()
                    uiSurface?.close()
                    context.close()
                }
            }.onFailure { it.printStackTrace() }
            teardownDone.countDown()
        }, "nuvio-teardown").start()
        while (!teardownDone.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            glfwPollEvents()
        }
        glfwDestroyWindow(videoWindow)
        glfwDestroyWindow(window)
        glfwTerminate()
    }
    println("OK: clean shutdown")
    exitProcess(exitCode)
}
