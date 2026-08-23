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
        jvmTarget.set(JvmTarget.JVM_17)
        // Driving ComposeScene directly is what lets us render without AWT.
        // It is deliberately not stable API, so opt in explicitly.
        optIn.addAll(
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.ui.InternalComposeUiApi",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.desktop.currentOs)

    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
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
    applicationDefaultJvmArgs = listOf(
        // GLFW on Wayland drives EGL; nothing here touches GLX or X11.
        "-Dorg.lwjgl.util.Debug=false",
    )
}
