import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: the Kotlin plugin is already on the build classpath via the
    // root project, so requesting a version here fails compatibility checking.
    kotlin("jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    application
}

// A Compose host that does not go through AWT.
//
// Compose Desktop normally renders into an AWT Canvas, and Skiko obtains that
// canvas's native surface through JAWT. No JDK implements JAWT for AWT's
// Wayland toolkit -- not upstream Wakefield and not JetBrains Runtime, whose
// libawt_wlawt.so exports zero JAWT symbols against libawt_xawt.so's six -- so
// Compose cannot start under -Dawt.toolkit.name=WLToolkit and the app silently
// runs on XWayland instead.
//
// Skiko's X11/GLX dependency is in its AWT windowing layer, not in its Skia
// binding: DirectContext.makeGL() binds to whatever GL context is already
// current. So driving ComposeScene ourselves, with a GLFW window and a context
// we create, sidesteps AWT entirely and runs natively on Wayland.
//
// Same shape as JetBrains' own AWT-free sample (experimental/lwjgl-integration).

val lwjglVersion = "3.3.6"

kotlin {
    compilerOptions {
        // FFM (java.lang.foreign) is used to bind libmpv, so this needs 22+.
        jvmTarget.set(JvmTarget.JVM_22)
        // Driving ComposeScene directly is what lets us render without AWT.
        // It is deliberately not stable API, so opt in explicitly.
        optIn.addAll(
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.ui.InternalComposeUiApi",
        )
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            // InternalKeyEvent is internal to compose-ui, and there is no public
            // way to synthesise a key event without an AWT KeyEvent -- which
            // would mean instantiating an AWT Component, the very thing this
            // module exists to avoid.
            "-Xdont-warn-on-error-suppression",
        )
    }
}

java {
    // No toolchain pin: use the JDK Gradle runs on, which must be 22 or newer
    // for the Foreign Function & Memory API.
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

dependencies {
    // The real application. Depending on it here is what lets the same UI run
    // on Wayland; nothing in composeApp needs to change.
    implementation(project(":composeApp"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.desktop.currentOs)

    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    // No natives-linux artifact exists for lwjgl-vulkan: on Linux it binds the
    // system libvulkan.so.1 at runtime (the natives jar is macOS-only, for
    // MoltenVK).
    implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:natives-linux")
    // LWJGL's bundled GLFW is a dual X11/Wayland build (glfwGetWaylandDisplay
    // is present), so it can be asked for Wayland directly. The system GLFW
    // cannot be substituted: LWJGL patches GLFW with extra IME entry points
    // such as glfwGetPreeditCursorRectangle, and loading a stock build fails
    // at init with "A required function is missing".
}

application {
    mainClass = "com.nuvio.wayland.MainKt"
    applicationDefaultJvmArgs = buildList {
        // GLFW on Wayland drives EGL; nothing here touches GLX or X11.
        add("-Dorg.lwjgl.util.Debug=false")
        // libmpv is bound through FFM rather than JNI, so no native build.
        add("--enable-native-access=ALL-UNNAMED")
        for (k in listOf(
            "media", "libmpv", "hwdec", "realApp", "probe", "videoLog",
            "smokePlayer", "demoFrames", "uiScale", "resizeTest", "subTest", "mpvExtra", "webChrome", "chromePage", "chromeProbe", "chromeBgRed", "chromeNoBlit", "chromeInitOnly", "vk", "paced", "sceneHoldMs", "noPlayerUi", "sampled", "uiThread", "uiFps",
        )) {
            providers.gradleProperty("nuvio.wayland.$k").orNull
                ?.let { add("-Dnuvio.wayland.$k=$it") }
        }
    }
}

// Headless proof of the Vulkan render path: no window, no GL, no compositor.
// Verifies the libmpv "vulkan" context, zero-copy nvdec, and that every target
// image's memory and semaphore export as fds a GL consumer could import.
tasks.register<JavaExec>("vkSmoke") {
    group = "verification"
    description = "Headless libmpv Vulkan render smoke test (VideoPipelineVk)"
    mainClass = "com.nuvio.wayland.VkSmokeKt"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    for (k in listOf("media", "libmpv", "hwdec", "videoLog")) {
        providers.gradleProperty("nuvio.wayland.$k").orNull
            ?.let { systemProperty("nuvio.wayland.$k", it) }
    }
}

// NVIDIA's DMABUF path exports the web chrome with destroyed alpha (the same
// degradation the stock bridge documents); disabling it makes the WPE web
// process hand over SHM buffers whose alpha is correct.
tasks.named<JavaExec>("run") {
    // Full software WebKit, same as upstream's linux branch (its
    // player_bridge.cpp documents why: the DMABUF/GPU paths yield chrome
    // frames with degraded or fully opaque alpha on NVIDIA). Cost is
    // controlled by the host's activity gate, not by the renderer.
    environment("WEBKIT_DISABLE_DMABUF_RENDERER", "1")
    environment("WEBKIT_DISABLE_COMPOSITING_MODE", "1")
}
