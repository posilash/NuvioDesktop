import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: the Kotlin plugin is already on the build classpath via the
    // root project, so requesting a version here fails compatibility checking.
    kotlin("jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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

// Two Compose artifacts ship the same lifecycle-runtime jar, which is fatal to
// distTar/installDist but harmless on a classpath.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Compose's own application DSL, not Gradle's `application` plugin: the two
// both register `run`, and this one also builds the jlink image the package
// installs, so an installed build needs no JRE.
compose.desktop.application {
    mainClass = "com.nuvio.wayland.MainKt"
    nativeDistributions {
        // The launcher, and so /opt/nuvio/bin, is named for the app.
        packageName = "Nuvio"
        packageVersion = "1.0.0"
        // The same set composeApp links, plus what this host adds: LWJGL
        // reaches for sun.misc.Unsafe, which lives in jdk.unsupported, and a
        // runtime without it dies at startup with NoClassDefFoundError.
        modules(
            "java.instrument",
            "java.management",
            "java.net.http",
            "jdk.httpserver",
            "jdk.unsupported",
        )
    }
    // Off for the same reason composeApp has it off: reflection-heavy Compose
    // and FFM bindings do not survive it.
    buildTypes.release.proguard {
        isEnabled.set(false)
    }
    jvmArgs += buildList {
        // GLFW on Wayland drives EGL; nothing here touches GLX or X11.
        add("-Dorg.lwjgl.util.Debug=false")
        // libmpv is bound through FFM rather than JNI, so no native build.
        add("--enable-native-access=ALL-UNNAMED")
        for (k in listOf(
            "media", "libmpv", "hwdec", "realApp", "harness", "probe", "videoLog",
            "smokePlayer", "demoFrames", "uiScale", "resizeTest", "subTest", "mpvExtra", "webChrome", "chromePage", "chromeProbe", "chromeBgRed", "chromeNoBlit", "chromeInitOnly", "vk", "paced", "sceneHoldMs", "noPlayerUi", "sampled", "uiThread", "uiFps",
            // Zero-copy GPU chrome and its levers.
            "chromeGpu", "chromeFps", "chromeSoftware", "chromeFlipGpu", "chromeFlipShm",
            "chromeAlwaysOn", "chromeScaleMul",
            // Test lever: the window size the host asks for, so chrome cost
            // can be measured against pixel area without touching the compositor.
            "winW", "winH",
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

// WEBKIT_DISABLE_DMABUF_RENDERER / WEBKIT_DISABLE_COMPOSITING_MODE used to be
// set here, unconditionally, which forced the web process to rasterize every
// chrome frame on the CPU at full window size -- fine in a window, the cause of
// the fullscreen lag. They now belong to the SHM path alone and are set from
// Kotlin (WpeChrome.init) only when that path is selected, still before any web
// process spawns. The GPU path wants neither.
