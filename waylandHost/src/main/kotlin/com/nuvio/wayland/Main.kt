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

// Overridable so chrome cost can be measured against pixel area by launching
// at different sizes, rather than by resizing the user's live window.
private val INITIAL_WIDTH =
    System.getProperty("nuvio.wayland.winW")?.toIntOrNull() ?: 1280
private val INITIAL_HEIGHT =
    System.getProperty("nuvio.wayland.winH")?.toIntOrNull() ?: 800

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

/**
 * Three pixels down the middle of the frame about to be shown, as hex.
 *
 * The startup trace can say a present carried no video and no chrome, but not
 * what the user was therefore looking at. These say it outright: the host's
 * clear colour means nothing at all was drawn over it, anything else means
 * something was -- which is the whole question when a flash is "still there".
 *
 * Diagnostic only, inside the startup window: glReadPixels stalls the pipe.
 */
private fun sampleFramebuffer(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "?"
    val px = java.nio.ByteBuffer.allocateDirect(4)
    val out = StringBuilder()
    for ((i, fraction) in listOf(0.25f, 0.5f, 0.85f).withIndex()) {
        px.clear()
        GL11.glReadPixels(
            width / 2, (height * fraction).toInt().coerceIn(0, height - 1), 1, 1,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px,
        )
        val v = (px.get(0).toInt() and 0xFF shl 16) or
            (px.get(1).toInt() and 0xFF shl 8) or (px.get(2).toInt() and 0xFF)
        if (i > 0) out.append('/')
        out.append("%06x".format(v))
    }
    return out.toString()
}

/**
 * Whether to compose the app rather than the input-echo harness.
 *
 * The app is the default: an installed launcher should not have to pass a flag
 * to get normal behaviour. -Dnuvio.wayland.harness=true selects the diagnostic
 * scene, and -Dnuvio.wayland.realApp=false still does too, since every script
 * and note from this port passes realApp explicitly.
 */
private fun runRealApp(): Boolean {
    if (System.getProperty("nuvio.wayland.harness")?.toBoolean() == true) return false
    return System.getProperty("nuvio.wayland.realApp")?.toBoolean() ?: true
}

/** Command line, for the deep links the app's own startup would handle. */
private var launchArgs: Array<String> = emptyArray()

/** Resident set size in MB, for the SIGKILL investigation: evidence, not theory. */
private fun rssMb(): Long = runCatching {
    java.io.File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024)
    } ?: -1
}.getOrDefault(-1)

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    launchArgs = args
    // LWJGL's per-thread MemoryStack default (64KB) is captured when the
    // class first loads -- which happens at glfwInit, so raising it later
    // (as the Vulkan pipeline needs: VkInstance enumerates ~230 device
    // extensions on the stack) has no effect. It must be first.
    org.lwjgl.system.Configuration.STACK_SIZE.set(512)
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

    // A Vulkan surface needs the window to have no client API: one wl_surface
    // has one buffer producer, and GLFW's EGL surface would be it. The scene
    // still renders with GL, from a context of its own -- see glOwner below.
    val vkSwapchain = System.getProperty("nuvio.wayland.vkSwapchain")?.toBoolean() == true

    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    if (vkSwapchain) glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)

    val window = glfwCreateWindow(INITIAL_WIDTH, INITIAL_HEIGHT, "Nuvio Wayland host", NULL, NULL)
    if (window == NULL) {
        glfwTerminate()
        error("glfwCreateWindow failed")
    }

    // Who owns the GL context the presenting thread renders with. Normally the
    // visible window; under a Vulkan swapchain it has no context to own, so an
    // invisible one stands in and every share group hangs off it instead.
    val glOwner: Long
    if (vkSwapchain) {
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glOwner = glfwCreateWindow(64, 64, "nuvio-gl", NULL, NULL)
        if (glOwner == NULL) {
            glfwTerminate()
            error("glfwCreateWindow (gl owner) failed")
        }
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    } else {
        glOwner = window
    }

    // Second, invisible window whose context shares objects with the first:
    // the video thread's home. Created here, before any context goes current
    // on another thread -- GLFW requires the share context not be current
    // elsewhere during creation. Textures made in one are usable in the other;
    // that sharing is what makes the video path zero-copy end to end.
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    val videoWindow = glfwCreateWindow(64, 64, "nuvio-video", NULL, glOwner)
    if (videoWindow == NULL) {
        glfwTerminate()
        error("glfwCreateWindow (video context) failed")
    }

    // Third invisible window, same share group: the Compose scene's home. It
    // exists for the same reason videoWindow does -- a GL context can only be
    // current on one thread, and the scene now rasterizes on its own. Created
    // here, before any context goes current elsewhere, because GLFW requires
    // the share context not be current on another thread during creation.
    val uiWindow = glfwCreateWindow(64, 64, "nuvio-ui", NULL, glOwner)
    if (uiWindow == NULL) {
        glfwTerminate()
        error("glfwCreateWindow (ui context) failed")
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

    // How the loop is paced. 2, the default, sets the swap interval to 0 and
    // holds the commit to the refresh grid itself, which is the shape mpv uses
    // (context_wayland.c). 1 is the swap interval, and on this compositor that
    // costs exactly half the refresh: Mesa blocks inside eglSwapBuffers and we
    // present at 82.5 of 165, as does weston-simple-egl, while mpv measures
    // 164.3 on the same output. 0 leaves it uncapped, which is how the loop's
    // own cost was measured: 443fps, 2.2ms of work.
    val vsyncMode = System.getProperty("nuvio.wayland.vsync")?.toIntOrNull() ?: 2
    val vblankNs = run {
        val mon = glfwGetPrimaryMonitor()
        val hz = if (mon != NULL) glfwGetVideoMode(mon)?.refreshRate() ?: 0 else 0
        if (hz > 0) 1_000_000_000L / hz else 1_000_000_000L / 60
    }
    // The grid the next commit is due on; 0 until the first frame anchors it.
    var nextSwapDueNs = 0L
    println("pace: vsyncMode=$vsyncMode vblank=${"%.2f".format(vblankNs / 1e6)}ms")

    java.awt.EventQueue.invokeAndWait {
        glfwMakeContextCurrent(glOwner)
        glfwSwapInterval(if (vsyncMode == 1) 1 else 0)
        GL.createCapabilities()
        println("GL_RENDERER: " + GL11.glGetString(GL11.GL_RENDERER))
        println("GL_VERSION:  " + GL11.glGetString(GL11.GL_VERSION))

        // Skiko binds to the context that is already current, so it never
        // touches GLX or X11 here. This is the whole reason the AWT-free path
        // works on Wayland while the AWT one cannot.
        context = DirectContext.makeGL()
    }

    // Owns the window's surface when there is no GL one. Bound before the Skia
    // surface is built: recreateSurface() wraps whatever framebuffer is current,
    // so binding here is what points the whole scene at the exported image.
    val presenter: VkPresenter? = if (vkSwapchain) VkPresenter(window) else null
    if (presenter != null) {
        java.awt.EventQueue.invokeAndWait {
            val fw = IntArray(1); val fh = IntArray(1)
            glfwGetFramebufferSize(window, fw, fh)
            presenter.init(maxOf(fw[0], 1), maxOf(fh[0], 1))
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, presenter.fbo)
        }
    }

    // Optional video layer. mpv renders on its own thread, into textures the
    // window context shares, via the render API -- no embedded window, no
    // "wid", no XComposite capture of an overlay. The api-type asks for the
    // libplacebo renderer, so this keeps vo=gpu-next quality rather than
    // dropping to the legacy one.
    val mediaUrl = System.getProperty("nuvio.wayland.media")
    val mpvPath = System.getProperty("nuvio.wayland.libmpv")
    val runRealAppEarly = runRealApp()
    var mpv: Mpv? = null
    var pipeline: DisplayPipeline? = null
    var videoHost: WaylandVideoHost? = null
    val videoFrameReady = java.util.concurrent.atomic.AtomicBoolean(false)
    if (mediaUrl != null || runRealAppEarly) {
        if (!Mpv.load(mpvPath)) {
            System.err.println(
                "FAIL: could not load libmpv (nuvio.wayland.libmpv=$mpvPath) " +
                    "-- ${Mpv.lastLoadError}",
            )
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
                // Timestamps on mpv's own lines, so its startup phases can be
                // lined up against the host's [session] traces.
                setOption("msg-time", "yes")
            }
            val userConf = userMpvConfPath()
            if (userConf != null) {
                val ok = loadConfigFile(userConf)
                println("mpv config: $userConf ${if (ok) "loaded" else "FAILED"}")
            } else {
                // NuvioLinux's built-in defaults, which it applies for exactly
                // this case: a user config is taken wholesale, and without one
                // the player would otherwise run on stock mpv defaults. Its
                // terminal and msg-level are left out -- this host logs through
                // them, and videoLog already decides how loud that is.
                println("mpv config: none found, applying built-in defaults")
                // Preferences, not invariants: an option this mpv does not
                // have is skipped rather than fatal. NuvioLinux also sets
                // vd-queue-min-bytes, which no mpv has -- the real queue
                // options are enable/max-secs/max-bytes/max-samples -- and
                // never notices because it ignores the return.
                for ((name, value) in listOf(
                    "cache" to "yes",
                    "cache-secs" to "300",
                    "demuxer-max-bytes" to "500M",
                    "demuxer-max-back-bytes" to "100M",
                    "keep-open" to "no",
                    "audio-file-auto" to "no",
                    "sub-auto" to "no",
                    "osd-level" to "0",
                    "input-default-bindings" to "no",
                    "input-vo-keyboard" to "no",
                    "video-sync" to "display-resample",
                    "video-sync-max-video-change" to "5",
                    // Frame queue: smooths out decode bursts.
                    "vd-queue-enable" to "yes",
                    "vd-queue-max-bytes" to "50000000",
                    "vd-queue-max-samples" to "30",
                )) {
                    runCatching { setOption(name, value) }
                        .onFailure { println("mpv default $name skipped: ${it.message}") }
                }
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
            // The host presents only when something changed, so when the UI
            // is static mpv's swap feedback sees frame-rate cadence, not
            // vsync cadence. Left to estimate the display rate from that,
            // mpv concluded "display FPS: 12", paced its renders to it, and
            // locked a death spiral (renders slow -> presents slow -> the
            // estimate stays low; measured as 14fps presents and hundreds of
            // dropped frames whenever startup jitter seeded a low estimate).
            // Tell it the real refresh rate instead.
            // Free-run does not need the hint, but without it mpv has no
            // display clock at all: no "Refresh Rate (specified)" in its
            // stats, video-sync stuck on audio, and A-V reported as residual
            // drift rather than driven to zero. GLFW reads the mode from the
            // system, so this is the monitor's real rate, not a guess.
            // -Pnuvio.wayland.displayFps=0 turns it off again.
            run {
                val mon = glfwGetPrimaryMonitor()
                val detected =
                    if (mon != NULL) glfwGetVideoMode(mon)?.refreshRate() ?: 0 else 0
                val hz = System.getProperty("nuvio.wayland.displayFps")?.toIntOrNull() ?: detected
                if (hz > 0) {
                    setOption("display-fps-override", hz.toString())
                    println("mpv display-fps-override=$hz (monitor reports $detected)")
                }
            }
            System.getProperty("nuvio.wayland.hwdec")?.let { setOption("hwdec", it) }
            initialize()
            // Now the config has been parsed, so video-sync is whatever the
            // user actually set. Nothing has created a render context yet,
            // which is the last moment this can be decided.
            val videoSync = getProperty("video-sync")
            Mpv.resolvePacedMode(videoSync)
            println(
                "mpv render: " + if (Mpv.pacedMode) {
                    "advanced control (video-sync=$videoSync) -- a stalled " +
                        "render is fatal here, not a repeated frame"
                } else {
                    "free-run (video-sync=$videoSync)"
                },
            )
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
        // Vulkan by default -- the user's reference build runs Vulkan for a
        // reason, and with the usage-bit and lifetime fixes it measures
        // 1.4-1.9ms renders with zero-copy nvdec. -Pnuvio.wayland.vk=false
        // selects the GL sample-at-present pipeline for A/B.
        // GL by default. Vulkan renders mpv fine (and gets zero-copy nvdec
        // from the fork), but it can only reach a GL/Skia window through an
        // interop handoff whose corruption showed up as flicker -- opt in
        // with -Pnuvio.wayland.vk=true when working on that path.
        val useVk = System.getProperty("nuvio.wayland.vk")?.toBoolean() == true
        pipeline = when {
            useVk ->
                // mpv renders with Vulkan -- the API the user's reference
                // build runs -- with zero-copy nvdec; the scene imports the
                // exported images into GL. See VkGlDisplayPipeline.
                VkGlDisplayPipeline(VideoPipelineVk(mpv!!))
            Mpv.pacedMode ->
                // The blocking-paced model needs its own thread.
                VideoPipeline(mpv!!, videoWindow)
            System.getProperty("nuvio.wayland.sampled")?.toBoolean() == true ->
                // Free-run GL, rendering at present time on the presenting
                // thread (Stremio's literal model). Opt-in only: it black
                // -screens the real app -- a Skia/mpv shared-context interop
                // problem that is still open. The threaded pipeline below is
                // the default.
                EdtSampledPipeline(mpv!!)
            else ->
                // Threaded free-run GL: this morning's verified-on-screen
                // configuration.
                VideoPipeline(mpv!!, videoWindow)
        }
        val edtSampled = pipeline is EdtSampledPipeline
        pipeline!!.apply {
            onFrame = {
                videoFrameReady.set(true)
                // The threaded GL pipeline wakes the loop itself; the others
                // are window-agnostic, so the wake lives here.
                if (useVk) glfwPostEmptyEvent()
            }
            probe = System.getProperty("nuvio.wayland.probe")?.toBoolean() ?: false
            if (edtSampled) {
                // Render context binds to the creating thread's GL context;
                // for the sampled pipeline that must be the EDT, which owns
                // the window context. Creation runs mpv/libplacebo GL init
                // behind Skia's back -- reset, or the scene draws black
                // from the very first frame.
                java.awt.EventQueue.invokeAndWait {
                    start()
                    context.resetGLAll()
                }
            } else {
                start()
            }
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
        println(
            "mpv render context: " +
                (if (useVk) Mpv.MPV_RENDER_API_TYPE_VULKAN else Mpv.MPV_RENDER_API_TYPE_OPENGL_NEXT) +
                " (video thread)",
        )
        // Hand the app a video sink so its player surface stops reaching
        // for SwingPanel, which needs an AWT-backed scene we do not have.
        videoHost = WaylandVideoHost(mpv!!, pipeline!!, context)
        if (runRealAppEarly) {
            com.nuvio.app.features.player.desktop.WaylandVideoBridge.delegate = videoHost
            if (System.getProperty("nuvio.wayland.noPlayerUi")?.toBoolean() == true) {
                // Diagnostic: tell the app the platform owns the player
                // chrome (so Compose draws none) without starting the web
                // chrome either -- video with no controls at all, to see
                // what the video path does with nothing drawn over it.
                com.nuvio.app.features.player.desktop.WaylandVideoBridge.webChromeActive = true
                println("player UI: DISABLED (no compose chrome, no web chrome)")
            }
            println("video bridge: installed")
        }
        if (mediaUrl != null) videoHost!!.markLoaded()
    }

    var width = INITIAL_WIDTH
    var height = INITIAL_HEIGHT
    var renderTarget: BackendRenderTarget? = null
    var surface: Surface? = null
    // The UI's own layer, legacy path only. The scene rasterizes here, only
    // when it has something new -- never on account of a video frame. With
    // threaded rasterization the equivalent layer is a texture published by
    // UiPipeline, so this surface is not allocated at all.
    var uiSurface: Surface? = null

    // Threaded UI rasterization: the scene gets its own thread, GL context and
    // Skia context, and publishes finished textures (see UiPipeline). The
    // presenting thread then only ever draws. -Pnuvio.wayland.uiThread=false
    // restores the in-loop rasterization this replaced, for A/B.
    val uiThreadEnabled = System.getProperty("nuvio.wayland.uiThread")?.toBoolean() ?: true

    fun recreateSurface() {
        surface?.close()
        renderTarget?.close()
        uiSurface?.close()
        // Explicit, not ambient: this wraps whatever framebuffer is bound, and
        // between the presenter's setup and here half the host's GL work runs.
        presenter?.let { GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, it.fbo) }
        renderTarget = BackendRenderTarget.makeGL(
            width, height, 0, 8,
            GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
            FramebufferFormat.GR_GL_RGBA8,
        )
        surface = Surface.makeFromBackendRenderTarget(
            context, renderTarget!!, SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        ) ?: error("Surface.makeFromBackendRenderTarget returned null")
        uiSurface = if (uiThreadEnabled) {
            null
        } else {
            Surface.makeRenderTarget(
                context, false,
                org.jetbrains.skia.ImageInfo.makeN32Premul(width, height),
            ) ?: error("Surface.makeRenderTarget (UI layer) returned null")
        }
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

    val initialScale = currentScale()
    var uiPipeline: UiPipeline? = null
    var uiLayer: UiLayer? = null
    // Set by the UI thread on publish; the analogue of videoFrameReady.
    val uiFrameReady = java.util.concurrent.atomic.AtomicBoolean(false)

    // Popup and dialog layers place themselves against WindowInfo.containerSize,
    // and the stock context reports zero, so they centre on the origin: the exit
    // modal measured 700x194 at -350,-97, a quarter of it on screen. Read live,
    // since a resize relayouts the scene and the layers re-place with it.
    val scenePlatformContext = object :
        androidx.compose.ui.platform.PlatformContext by androidx.compose.ui.platform.PlatformContext.Empty() {
        override val windowInfo = object : androidx.compose.ui.platform.WindowInfo {
            override val isWindowFocused: Boolean
                get() = glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE
            override val containerSize: androidx.compose.ui.unit.IntSize
                get() = androidx.compose.ui.unit.IntSize(width, height)
        }
    }

    // 1.12.10 made the graphics backend pluggable and stopped registering it
    // itself: without this the scene fails to build with "Registered
    // implementation is null". It is also where Graphite would be selected.
    androidx.compose.ui.platform.registerSkikoComposeImplementation()

    val scene: ComposeScene
    // Whichever recomposer drives `scene`: the pipeline's on the threaded path,
    // one of our own on the legacy in-loop path.
    val sceneRecomposer: androidx.compose.ui.platform.FrameRecomposer
    if (uiThreadEnabled) {
        val p = UiPipeline(uiWindow)
        p.onFrame = {
            uiFrameReady.set(true)
            // Wake the host loop even if it is idle in glfwWaitEventsTimeout.
            glfwPostEmptyEvent()
        }
        // The scene is CONSTRUCTED on the UI thread, with a dispatcher that
        // posts back to it: everything Compose launches -- effects, animations,
        // recomposition -- then runs on the one thread that is allowed to touch
        // the scene. MainUIDispatcher/the EDT is out of the picture entirely.
        p.start(width, height, initialScale) { recomposer ->
            CanvasLayersComposeScene(
                frameRecomposer = recomposer,
                density = Density(initialScale),
                size = androidx.compose.ui.unit.IntSize(width, height),
                platformContext = scenePlatformContext,
            )
        }
        p.awaitReady()
        uiPipeline = p
        uiLayer = UiLayer(context)
        scene = p.scene
        sceneRecomposer = p.frameRecomposer
        println("ui: threaded rasterization (nuvio-ui thread, shared GL context + own DirectContext)")
    } else {
        sceneRecomposer = androidx.compose.ui.platform.FrameRecomposer(MainUIDispatcher) {}
        scene = CanvasLayersComposeScene(
            frameRecomposer = sceneRecomposer,
            density = Density(initialScale),
            size = androidx.compose.ui.unit.IntSize(width, height),
            platformContext = scenePlatformContext,
        )
        println("ui: in-loop rasterization on the EDT (legacy path, -Pnuvio.wayland.uiThread=false)")
    }

    // Every scene touch goes through these, so the owning thread is decided in
    // exactly one place.
    fun onSceneThread(block: () -> Unit) {
        val p = uiPipeline
        if (p != null) p.post(block) else java.awt.EventQueue.invokeLater(block)
    }
    fun onSceneThreadAndWait(block: () -> Unit) {
        val p = uiPipeline
        if (p != null) p.invokeAndWait(block) else java.awt.EventQueue.invokeAndWait(block)
    }

    val runRealApp = runRealApp()
    // Drives the app's own player surface directly, so playback can be
    // exercised without clicking through the UI. Mirrors Main.kt's
    // smokePlayerUrl harness.
    val smokePlayerUrl = System.getProperty("nuvio.wayland.smokePlayer")
    onSceneThreadAndWait {
    if (runRealApp) {
        // The preamble Main.kt runs before showing its window: QuickJS for
        // plugins, the URI handler, launch args, cached profiles, Discord.
        // What it leaves out, and why, is documented there.
        com.nuvio.app.startDesktopRuntimeWithoutWindow(launchArgs)
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
    var chromeLayer: ChromeLayer? = null
    // Legacy in-loop consumer only (uiThread=false): with the UI thread the
    // chrome is a texture composited there, and this stays null.
    var chromeImage: org.jetbrains.skia.Image? = null
    var chromeShown = true
    // Zero-copy GPU chrome: WebKit renders on the GPU and the exported
    // EGLImage is imported as a texture on the UI thread, so no chrome frame
    // is ever copied and the per-frame CPU cost stops scaling with area.
    // false selects the software SHM path, kept for A/B.
    val chromeGpuRequested = System.getProperty("nuvio.wayland.chromeGpu")?.toBoolean() ?: true
    // Defeats the activity gate so chrome cost can be measured against rolling
    // video. Diagnostic only; the gate's real semantics are untouched.
    val chromeAlwaysOn = System.getProperty("nuvio.wayland.chromeAlwaysOn")?.toBoolean() ?: false
    // Multiplies the chrome's device scale factor, so WebKit rasters and
    // exports a LARGER buffer while the window stays exactly where it is.
    // Purely a measurement lever: under a tiling compositor the window size
    // is not ours to choose, and driving the compositor to get one is off
    // limits, but chrome cost has to be shown against pixel area somehow.
    val chromeScaleMul =
        System.getProperty("nuvio.wayland.chromeScaleMul")?.toFloatOrNull() ?: 1f
    // Nanotime until which the chrome layer is considered active. The linux
    // branch's rule, copied: composite only while loading/paused/interacting
    // (plus fade grace) -- "normal watching pays nothing".
    val chromeActiveUntilNs = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
    // When the chrome may be shown is WpeChrome's own gate now: it is armed
    // where this session's state is pushed into the page, and opens when the
    // layer holds a frame painted from it. See WpeChrome's reveal gate.
    var chromeEpochSeen = 0L
    // The stock web chrome is the player UI: it renders on the GPU, costs
    // ~0.09ms a frame regardless of window size, and leaves Compose with
    // nothing to draw during playback. -Pnuvio.wayland.webChrome=false falls
    // back to Compose-drawn controls.
    if (System.getProperty("nuvio.wayland.webChrome")?.toBoolean() != false) {
        // Resolve the page against the repo root regardless of working dir.
        // An installed build has no repo, so the override is checked first --
        // looking for the source tree and failing before reading it left the
        // packaged host dead on arrival.
        // The app's own exporter: it unpacks the page to a versioned cache with
        // the @font-face rules and the JetBrains Sans faces substituted in.
        // Reading controls.css off the source tree instead left the
        // __NUVIO_PLAYER_FONT_FACES__ placeholder unreplaced, so the chrome
        // rendered in fallback fonts.
        val pageUri = System.getProperty("nuvio.wayland.chromePage")
            ?: com.nuvio.app.desktopControlsPageUrl()
        wpeChrome = com.nuvio.wayland.wpe.WpeChrome(
            width = INITIAL_WIDTH,
            height = INITIAL_HEIGHT,
            // The GPU path needs a GL context to import the exported EGLImage
            // into, and that context is the UI thread's. On the legacy in-loop
            // path there is no such thread, so WPE stays on SHM.
            allowGpu = chromeGpuRequested && uiPipeline != null,
            onMessage = { json ->
                // {type,value} in either field order; value optional.
                val type = Regex("\u0022type\u0022[ \t]*:[ \t]*\u0022([^\u0022]*)\u0022")
                    .find(json)?.groupValues?.get(1)
                val value = Regex("\u0022value\u0022[ \t]*:[ \t]*(-?[0-9.]+)")
                    .find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                if (type != null) {
                    if (System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true) {
                        println("[wpe] event: $type=$value")
                    }
                    // The linux branch's overlayActive latch, verbatim: the
                    // page's own signals say when the chrome is on screen.
                    when (type) {
                        "keepChromeVisible", "cursorActivity", "toggleChrome",
                        "controlsReady" ->
                            chromeActiveUntilNs.set(System.nanoTime() + 5_000_000_000L)
                        "hideChrome" ->
                            // Fade grace: the hide animation still needs frames.
                            chromeActiveUntilNs.set(System.nanoTime() + 600_000_000L)
                    }
                    if (type == "controlsReady") {
                        // A fresh page context. Everything painted before it
                        // belongs to a page that no longer exists, so the gate
                        // shuts and is re-armed from here.
                        wpeChrome?.closeReveal()
                        // Only feed a page that has a session to show. The
                        // controls page starts with isLoading=true and shows
                        // its opening overlay while `!hasReceivedPlayerControls`
                        // -- and receiving ANY controls JSON clears that flag,
                        // so a warm-up push is what makes a fresh page render
                        // controls instead of the overlay.
                        if (videoHost?.hasFile == true) {
                            // Re-arms on the push, so the reveal waits for a
                            // frame painted from THIS session's state. Covers
                            // the race where the session's own push landed on
                            // a page that had not finished loading.
                            videoHost?.flushControlsToChrome()
                        } else {
                            // No session to feed: the page's own paint is all
                            // there will be (host-only chrome).
                            wpeChrome?.armReveal()
                        }
                    }
                    java.awt.EventQueue.invokeLater {
                        com.nuvio.app.features.player.desktop.WaylandVideoBridge
                            .onChromeEvent?.invoke(type, value)
                    }
                }
            },
        ).also { chrome ->
            chrome.start(pageUri)
        }
        videoHost?.chrome = wpeChrome
        // Chrome frames are consumed on the PRESENTING thread, not the UI one.
        //
        // The UI thread rasterizes Compose, and a Compose frame is not
        // bounded: measured at 370ms on a screen with many dated items, all of
        // it inside scene.render(). Adopting chrome frames there meant the
        // controls froze for exactly as long, even though the chrome's own
        // work is 0.09ms. The AWT build never showed this because its chrome
        // is a separate GTK window that Compose cannot stall.
        //
        // The import needs a current GL context, and the window's is in the
        // same share group, so the presenting thread can do it just as well.
        chromeLayer = ChromeLayer(wpeChrome!!)
        wpeChrome!!.onFrame = { glfwPostEmptyEvent() }
        if (runRealAppEarly) {
            com.nuvio.app.features.player.desktop.WaylandVideoBridge.webChromeActive = true
            println("web chrome: ACTIVE (stock controls.html via WPE)")
        }
    }

    val input = InputRouter(window, scene)
    // GLFW callbacks still fire on the main thread; they must hand off to
    // whichever thread owns the scene, which is no longer necessarily the EDT.
    uiPipeline?.let { p -> input.dispatch = { block -> p.post(block) } }
    input.install()
    // Only while something is playing: off the player these are plain Back and
    // Forward, and MainAppContent already pops the back stack on one of them.
    input.onThumbButton = { type ->
        if (videoHost?.hasFile == true) {
            java.awt.EventQueue.invokeLater {
                com.nuvio.app.features.player.desktop.WaylandVideoBridge
                    .onChromeEvent?.invoke(type, 0.0)
            }
        }
    }
    wpeChrome?.let { c ->
        input.chrome = c
        // Long enough for the page's 1400ms toast and its fade. Compositing a
        // page whose controls are hidden draws only the toast, so this shows
        // no more than upstream does with its always-composited view.
        input.onChromeInput = {
            chromeActiveUntilNs.set(System.nanoTime() + 2_000_000_000L)
        }
        // With a device scale factor the page hit-tests in PHYSICAL pixels
        // (Cog multiplies surface coords by the output scale the same way);
        // InputRouter's cursor is already framebuffer pixels, so 1:1. On a
        // compositor without viewporter the page runs at logical size and
        // coordinates scale down instead.
        input.chromeScaleX = 1f
        input.chromeScaleY = 1f
    }

    // Keyboard needs an owner. On the AWT path the window grants Compose focus
    // when it gains it; with a bare scene nothing does, so every key event is
    // delivered and then dropped by the focus system. Mirror what a window
    // does: take focus now, and follow the compositor's focus from then on.
    onSceneThread {
        scene.focusManager.takeFocus(androidx.compose.ui.focus.FocusDirection.Next)
    }
    glfwSetWindowFocusCallback(window) { _, focused ->
        onSceneThread {
            if (focused) {
                scene.focusManager.takeFocus(androidx.compose.ui.focus.FocusDirection.Next)
            } else {
                scene.focusManager.releaseFocus()
            }
        }
    }

    var exitCode = 0
    var lastChromeTickNs = 0L
    var lastUiReportNs = System.nanoTime()
    // Diagnostic lever: how long scene rasters are held during playback.
    val sceneHoldNs = (System.getProperty("nuvio.wayland.sceneHoldMs")?.toLongOrNull() ?: 250L) * 1_000_000L
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
    // Which target image the Skia surface was built against.
    var presenterGeneration = 0

    /** Returns true if this iteration actually presented a frame. */
    fun renderOneFrame(): Boolean {

        val w = IntArray(1); val h = IntArray(1)
        // With a Vulkan swapchain the window has no framebuffer to measure;
        // the surface's extent is the size, and the presenter reports it.
        if (presenter != null) {
            w[0] = presenter.width; h[0] = presenter.height
        } else {
            glfwGetFramebufferSize(window, w, h)
        }
        val targetChanged = presenter != null && presenter.generation != presenterGeneration
        if (w[0] != width || h[0] != height || targetChanged) {
            presenterGeneration = presenter?.generation ?: 0
            width = w[0]; height = h[0]
            if (width > 0 && height > 0) {
                recreateSurface()
                // The presenter deletes and recreates its texture and FBO on a
                // rebuild, and GL hands back the same ids -- so Skia's cached
                // state describes an attachment that no longer exists.
                if (presenter != null) context.resetGLAll()
                forceRepaint = true
                // Pointer positions arrive in window coordinates but the
                // scene works in framebuffer pixels; on a fractionally
                // scaled output those differ. The same ratio is the UI
                // density, which can change when the window moves outputs.
                val scale = currentScale()
                input.scale = scale
                val p = uiPipeline
                if (p != null) {
                    // Size and density are scene state, so they are applied on
                    // the scene's own thread; the pipeline reallocates its
                    // textures to match on the next frame.
                    p.resize(width, height, scale)
                } else {
                    scene.size = androidx.compose.ui.unit.IntSize(width, height)
                    if (scene.density.density != scale) scene.density = Density(scale)
                }
                val logicalW = Math.round(width / scale)
                val logicalH = Math.round(height / scale)
                if (videoLog) {
                    println("[chrome-size] fb=${width}x$height logical=${logicalW}x$logicalH scale=$scale")
                }
                // set_size IS the CSS layout size (WPEWebViewLegacy:
                // set_size -> view.setSize verbatim), so it must be LOGICAL;
                // the scale factor makes WebKit raster it at logical*scale =
                // physical, which this scene then draws 1:1.
                wpeChrome?.dispatchScale(scale * chromeScaleMul)
                wpeChrome?.dispatchSize(logicalW, logicalH)
            }
        }

        val s = surface ?: return false
        // Legacy path only: with threaded rasterization the UI layer is a
        // published texture, not a surface this thread draws into.
        val ui = uiSurface
        if (ui == null && uiPipeline == null) return false

        if (videoLog) {
            wpeChrome?.let { chrome ->
                if (frames % 120 == 0) {
                    println("[wpe] exported=${chrome.framesExported} err=${chrome.lastError}")
                }
            }
            videoHost?.report(System.nanoTime())?.let {
                println("[wayland-video] $it rss=${rssMb()}MB heap=${Runtime.getRuntime().let { r -> (r.totalMemory() - r.freeMemory()) / 1_048_576 }}MB")
            }
            // Scene cost is reported from the thread that pays it; it no
            // longer appears anywhere on this present path.
            uiPipeline?.let { p ->
                if (System.nanoTime() - lastUiReportNs > 1_000_000_000L) {
                    val elapsed = (System.nanoTime() - lastUiReportNs) / 1e9
                    lastUiReportNs = System.nanoTime()
                    println("[wayland-video] ${p.report(elapsed)}")
                    // Chrome cost is reported from the thread that pays it.
                    // perMpx is the number that matters: it is what used to
                    // grow with the window and made fullscreen chrome lag.
                    chromeLayer?.let { l -> println("[wayland-video] ${l.report(elapsed)}") }
                }
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
        // The chrome takes no part in this loop: it is a compositor-layered
        // subsurface fed on the GLib thread. Keeping it (and every other
        // foreign concern) out of this window's GL and present cadence is
        // what preserves the measured-healthy video path.
        var chromeChanged = false
        wpeChrome?.let { chrome ->
            // Activity gate (linux-branch parity): while video plays with the
            // chrome hidden, the page is starved down to a trickle and this
            // loop does no chrome work at all. Paused/loading playback is
            // free for mpv, so the chrome runs whenever video is not rolling.
            val playing = videoHost?.hasFile == true && !(videoHost?.isPausedOrLoading() ?: true)
            // chromeAlwaysOn is a measurement lever, not a behaviour change:
            // the gate normally hides the chrome seconds into playback, which
            // is correct but leaves nothing to measure. Holding it visible
            // over rolling video is the case the user actually complains
            // about, so it is the case the numbers have to come from.
            val active = chromeAlwaysOn ||
                !playing || System.nanoTime() < chromeActiveUntilNs.get()
            // A reload resets the page to bootstrap: hide it again until it
            // reports ready and has drawn the state we push.
            if (chrome.sessionEpoch != chromeEpochSeen) {
                chromeEpochSeen = chrome.sessionEpoch
                chrome.closeReveal()
            }
            val chromeDrawn = chrome.revealReady
            val wantChrome = (!runRealApp || videoHost?.hasFile == true) &&
                active && chromeDrawn
            if (videoLog && wantChrome != chromeShown) {
                println(
                    "[session] chrome ${if (wantChrome) "SHOW" else "hide"} " +
                        "(hasFile=${videoHost?.hasFile} active=$active " +
                        "drawn=$chromeDrawn taken=${chrome.framesTaken})",
                )
                StartupTrace.mark(
                    "chrome ${if (wantChrome) "SHOW" else "hide"} " +
                        "(layerHasContent=${chromeLayer?.hasContent})",
                )
            }
            if (wantChrome != chromeShown) {
                chromeShown = wantChrome
                chrome.visible = wantChrome
                // The session-start window is over either way: shown means the
                // layer has what it was primed for, hidden means there is
                // nothing worth keeping.
                chrome.priming = false
                val ui = uiPipeline
                if (!wantChrome) {
                    chromeImage?.close()
                    chromeImage = null
                    // Two different hides. The inactivity one happens many
                    // times a session -- the controls fade out, the mouse
                    // moves, they come back -- and there the last frame is
                    // the page's own faded-out one, which is exactly what
                    // the next reveal should draw. Throwing it away made
                    // every re-show blank until a fresh frame arrived.
                    // Only the end of a SESSION invalidates the texture,
                    // because only then does it belong to a stream that is
                    // no longer playing.
                    //
                    // This thread owns the layer now, so the teardown is a
                    // plain call: no posting, and none of the racing that
                    // came with it.
                    val sessionOver = videoHost?.hasFile != true
                    chromeLayer?.let { if (sessionOver) it.clear() else it.setComposited(false) }
                    chromeChanged = true
                } else {
                    // Nothing to wait for: priming already put this session's
                    // opening overlay in the texture, so the reveal is just
                    // permission to draw it. The ack keeps the page running
                    // now that its frames are worth having again.
                    chromeLayer?.setComposited(true)
                    chromeChanged = true
                    chrome.ackFrame()
                }
            }
            // Chrome frames ride this scene, Stremio-style: ONE surface,
            // ONE present stream. (A separate subsurface commit stream made
            // the chrome and the video fight in the compositor whenever
            // both were animating.)
            //
            // With the UI thread present, adoption happens THERE -- see
            // ChromeLayer -- and this loop never touches a chrome frame. What
            // follows is the legacy in-loop consumer, which pays a full-frame
            // raster rebuild per frame and is exactly the cost the GPU path
            // exists to delete.
            if (uiPipeline == null) {
                chrome.takeShmFrame()?.let { frame ->
                    chromeImage?.close()
                    chromeImage = org.jetbrains.skia.Image.makeRaster(
                        org.jetbrains.skia.ImageInfo(
                            frame.width, frame.height,
                            org.jetbrains.skia.ColorType.BGRA_8888,
                            org.jetbrains.skia.ColorAlphaType.PREMUL,
                        ),
                        frame.pixels.let { b -> ByteArray(b.remaining()).also { a -> b.get(a) } },
                        frame.width * 4,
                    )
                    chromeChanged = true
                    // The delayed ack is the page's frame budget: never faster
                    // than ~30fps, no matter how fast this loop spins. Every
                    // un-throttled ack scheme so far turned into the page
                    // re-rendering at whatever rate the acking thread ran.
                    // Same reasoning as ChromeLayer.ackDelayMs: only the
                    // CPU path needs a frame budget.
                    chrome.ackFrameAfter(if (chrome.gpuActive) 8 else 33)
                }
            }
        }
        // With threaded rasterization this thread never asks the scene
        // anything -- hasInvalidations() would be a cross-thread read of
        // Compose internals. The UI thread's publish is the signal instead.
        val uiChanged = uiFrameReady.getAndSet(false)
        val sceneDirty = if (uiPipeline != null) {
            forceRepaint || uiChanged
        } else {
            forceRepaint || sceneRecomposer.hasPendingWork()
        }
        // Stremio's presentation model, completed: while video plays, present
        // EVERY iteration -- vsync-paced by the swap -- and sample mpv's
        // latest frame. Present-on-arrival exposed the core's frame-delivery
        // jitter as a visible wobble (frames landing a vsync early/late);
        // continuous presents give 24-in-165 the steady pulldown a sampling
        // player is supposed to have. Safe now that free-run mode reports no
        // swaps to mpv: the old continuous-present experiment spiralled only
        // because paced mode fed those noisy timestamps back as vsync hints.
        // Idle (no file, or paused beyond a grace) stays demand-driven.
        val videoRolling = videoHost?.hasFile == true &&
            !(videoHost?.isPausedOrLoading() ?: true) && !Mpv.pacedMode
        if (!videoChanged && !sceneDirty && !chromeChanged && !videoRolling) return false

        // A scene rasterization is uninterruptible once started; at fullscreen
        // it can take 20-50ms, and a video frame arriving mid-raster queues
        // behind it -- the measured 11-vsync gaps. So the scene only starts
        // when it can finish before the next frame is due. When the UI is
        // heavier than the gap between frames, chrome updates degrade and the
        // video cadence stays intact, which is the right way round.
        var t = System.nanoTime()
        // While video rolls in sampling mode, scene rasterizations are the
        // only variable-cost work left in this loop; each one knocks the
        // present off its vsync slot -- a once-per-state-tick micro-hiccup
        // (Stremio never pays this: its scene is constant-cheap because the
        // web engine's raster work lives out of process). Throttling rasters
        // to 4/s keeps the sampling grid steady; anything Compose needs to
        // show still appears within 250ms.
        // The hold is a property of in-loop rasterization: it exists to stop a
        // scene render from landing on top of a video frame. With the scene on
        // its own thread there is no such collision to throttle, so the lever
        // is bypassed entirely rather than tuned (it stays live on the legacy
        // path for A/B).
        val rasterThrottled = uiPipeline == null && videoRolling &&
            (t - lastSceneRenderNs) < sceneHoldNs
        if (ui != null && sceneDirty && !videoChanged && !rasterThrottled) {
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
            sceneRecomposer.performFrame(System.nanoTime())
            scene.draw(ui.canvas.asComposeCanvas())
            val costMs = (System.nanoTime() - t) / 1e6
            sceneCostEmaMs += (costMs - sceneCostEmaMs) * 0.2
            timings.add("scene", System.nanoTime() - t)
        }

        // The stock bridge's periodic playback push, off the property cache.
        if (wpeChrome != null && System.nanoTime() - lastChromeTickNs > 300_000_000L) {
            lastChromeTickNs = System.nanoTime()
            videoHost?.pushPlaybackUpdate()
        }

        t = System.nanoTime()
        s.canvas.clear(0xFF101014.toInt())
        videoHost?.compositeVideo(s.canvas)

        // Surface.draw, not makeImageSnapshot: a snapshot makes the next
        // scene render pay a full-resolution copy-on-write of the UI texture
        // (14MB at 2560x1440), which is what inflated scene costs to tens of
        // milliseconds and dragged the whole chrome down.
        var uiGen = -1
        var uiFresh = false
        if (ui != null) {
            ui.draw(s.canvas, 0, 0, null)
        } else {
            // Threaded path: draw whatever the UI thread published last. This
            // is the entire UI cost of a present -- one textured quad, the same
            // shape as the video composite above, and it does not vary with
            // how expensive the scene happens to be.
            uiPipeline?.acquireFrame()?.let { f ->
                uiGen = f.generation
                uiFresh = f.fresh
                uiLayer?.draw(s.canvas, f)
            }
        }
        // What this present actually put on screen. A run of these with no
        // video and no chrome IS the flash, counted in frames.
        // Chrome above everything: a raster image draw, which Skia
        // composites with proper premultiplied alpha (its opaque-surface
        // rule only applies to wrapped render targets). The buffer is
        // already physical resolution, so this is a crisp 1:1 blit.
        // Legacy path only -- with the UI thread the chrome is already inside
        // the texture drawn just above.
        chromeImage?.let { s.canvas.drawImage(it, 0f, 0f) }

        // The presenting thread's whole variable cost, in one number: video
        // quad + UI quad (+ legacy chrome). This is the figure that must stay
        // flat as the window grows.
        timings.add("composite", System.nanoTime() - t)

        t = System.nanoTime()
        context.flush()
        timings.add("flush", System.nanoTime() - t)

        // Chrome last, straight into the window's framebuffer with raw GL --
        // after Skia's work is submitted, so it lands on top, and with Skia's
        // cached state invalidated after, since this happens behind its back.
        // Adopting here rather than on the UI thread is what keeps the
        // controls responsive while Compose is busy.
        chromeLayer?.let { l ->
            l.update()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, presenter?.fbo ?: 0)
            l.draw(width, height, originBottomLeft = true)
            context.resetGLAll()
        }

        // What this present actually put on screen, read back from the frame
        // itself. A run of these with no video and no chrome is the flash --
        // and the pixels say what colour it is, which is the difference
        // between "nothing was drawn" (the host's clear colour) and "Compose
        // drew a screen we did not expect". Only inside the startup window,
        // and a stall of a few pixels at that.
        if (StartupTrace.active) {
            StartupTrace.present(
                "video=${if (videoHost?.drewVideo == true) "yes" else "no "} " +
                    "chrome=${if (chromeLayer?.isComposited == true) "on " else "off"} " +
                    "uiGen=$uiGen px=${sampleFramebuffer(width, height)}",
            )
        }

        // Throwaway subtitle harness: white text in the bottom third means
        // subs render; zero bright pixels means they are lost in the pipeline.
        if (System.getProperty("nuvio.wayland.subTest")?.toBoolean() == true && frames % 60 == 30) {
            // Whole-frame scan: distinguishes "not rendered" from "rendered in
            // the wrong place". Reports the y-range of near-white pixels.
            val buf = java.nio.ByteBuffer.allocateDirect(width * height * 4)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, presenter?.fbo ?: 0)
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
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, presenter?.fbo ?: 0)
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
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, presenter?.fbo ?: 0)
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
        // Hold the commit to the refresh grid before swapping, not after: the
        // deadline is when the buffer must be on the compositor's queue, so
        // waiting here lands every commit on the grid whatever the frame cost.
        if (vsyncMode == 2) {
            val now = System.nanoTime()
            // A stall longer than a frame means the grid is stale; re-anchor
            // rather than burning a burst of catch-up commits.
            if (nextSwapDueNs == 0L || now > nextSwapDueNs + vblankNs) {
                nextSwapDueNs = now
            } else {
                val remain = nextSwapDueNs - now
                // parkNanos overshoots by about a millisecond, which is a sixth
                // of the interval here, so it only covers the bulk of the wait.
                if (remain > 300_000L) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remain - 300_000L)
                }
                while (System.nanoTime() < nextSwapDueNs) Thread.onSpinWait()
            }
            nextSwapDueNs += vblankNs
        }
        t = System.nanoTime()
        if (presenter != null) presenter.present() else glfwSwapBuffers(window)
        timings.add("swap", System.nanoTime() - t)
        // Vsync feedback: with ADVANCED_CONTROL this is the clock mpv times
        // against, and its contract is one call per swap -- what a real VO gets
        // from its swapchain. Reporting only when a video frame changed made
        // the estimate circular: mpv counted our reports as vsyncs, so it
        // measured the display at the rate it was feeding us. It settled on
        // 81Hz against a real 165 and mistimed every frame it scheduled.
        mpv?.reportSwap()
        if (videoChanged) {
            noteVideoPresent(System.nanoTime())
            // The safest moment to rasterize the scene is right here, just
            // after a frame was presented: the full frame interval lies
            // ahead. Deferring it to the next loop iteration burned 4-12ms
            // of that margin and let 20ms scenes collide with the next
            // frame -- the residual 11-vsync gaps.
            if (ui != null && sceneRecomposer.hasPendingWork() &&
                sceneCostEmaMs < (pipeline?.publishIntervalMs ?: 41.7) - 8.0
            ) {
                val ts = System.nanoTime()
                forceRepaint = false
                ui.canvas.clear(0x00000000)
                sceneRecomposer.performFrame(System.nanoTime())
            scene.draw(ui.canvas.asComposeCanvas())
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

    // The chrome view must match the window from the first frame, not only
    // after a resize -- a stale 1280x800 view under a differently-sized
    // window skews every click through the stretch factor. Logical size: the
    // subsurface buffer is 1:1 with surface coordinates.
    run {
        val ww = IntArray(1); val wh = IntArray(1)
        glfwGetWindowSize(window, ww, wh)
        val fw = IntArray(1); val fh = IntArray(1)
        glfwGetFramebufferSize(window, fw, fh)
        if (ww[0] > 0) {
            // Logical size + scale factor: see the resize handler.
            wpeChrome?.dispatchScale(currentScale() * chromeScaleMul)
            wpeChrome?.dispatchSize(ww[0], wh[0])
        }
    }

    var presented = true
    try {
        while (!glfwWindowShouldClose(window)) {
            // Input callbacks fire from here, on the main thread, and are
            // forwarded to the EDT by InputRouter.
            val beforePoll = System.nanoTime()
            // When the last iteration presented, the swap paces us -- on the
            // grid in mode 2, blocking in mode 1. When it did not, there is
            // nothing to wait on, so wait for input with a short timeout
            // instead of spinning -- Compose's own
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
                // The scene and its Skia context are owned by whichever thread
                // rasterized them and must die there. Ordering is unchanged:
                // scene first (its disposals stop playback through a live mpv),
                // then the core, then the video pipeline.
                val p = uiPipeline
                if (p != null) p.stop() else java.awt.EventQueue.invokeAndWait { scene.close() }
                mpv?.quitAndAwaitShutdown()
                if (pipeline is EdtSampledPipeline) {
                    java.awt.EventQueue.invokeAndWait { pipeline?.stop() }
                } else {
                    pipeline?.stop()
                }
                mpv?.close()
                java.awt.EventQueue.invokeAndWait {
                    // uiLayer's wrappers live in the window context, so they
                    // go here, not with the UI thread's own objects.
                    uiLayer?.close()
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
        chromeImage?.close()
        glfwDestroyWindow(uiWindow)
        glfwDestroyWindow(videoWindow)
        glfwDestroyWindow(window)
        glfwTerminate()
    }
    println("OK: clean shutdown")
    exitProcess(exitCode)
}
