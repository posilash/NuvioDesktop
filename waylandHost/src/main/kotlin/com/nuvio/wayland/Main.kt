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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
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

    glfwMakeContextCurrent(window)
    glfwSwapInterval(1)
    GL.createCapabilities()
    println("GL_RENDERER: " + GL11.glGetString(GL11.GL_RENDERER))
    println("GL_VERSION:  " + GL11.glGetString(GL11.GL_VERSION))

    // Skiko binds to the context that is already current, so it never touches
    // GLX or X11 here. This is the whole reason the AWT-free path works on
    // Wayland while the AWT one cannot.
    val context = DirectContext.makeGL()

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
    recreateSurface()

    var frames by mutableStateOf(0)

    val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(1f),
        size = androidx.compose.ui.unit.IntSize(width, height),
        coroutineContext = MainUIDispatcher,
    )
    scene.setContent {
        Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
            Column(Modifier.padding(32.dp)) {
                Text("Compose on Wayland, without AWT", color = Color(0xFFE0E0E0))
                Text("GLFW platform: $platformName", color = Color(0xFF9AD29A))
                Text("frames: $frames", color = Color(0xFF9AA0D2))
            }
        }
    }

    var exitCode = 0
    try {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()

            val w = IntArray(1); val h = IntArray(1)
            glfwGetFramebufferSize(window, w, h)
            if (w[0] != width || h[0] != height) {
                width = w[0]; height = h[0]
                if (width > 0 && height > 0) {
                    recreateSurface()
                    scene.size = androidx.compose.ui.unit.IntSize(width, height)
                }
            }

            val s = surface ?: break
            s.canvas.clear(0xFF101014.toInt())
            scene.render(s.canvas.asComposeCanvas(), System.nanoTime())
            context.flush()
            glfwSwapBuffers(window)

            frames++
            if (frames == 120) {
                println("RESULT: rendered 120 frames on $platformName")
                if (platformName != "Wayland") {
                    System.err.println("FAIL: not running on Wayland")
                    exitCode = 1
                }
                glfwSetWindowShouldClose(window, true)
            }
        }
    } finally {
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
