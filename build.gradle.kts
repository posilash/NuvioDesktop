plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.sentry.android.gradle) apply false
    alias(libs.plugins.sentry.jvm.gradle) apply false
}

// Skiko, patched so RenderNode replays a picture instead of a drawable that
// Graphite silently discards -- without it every Compose layer, and so the
// whole scene, draws nothing on a Vulkan surface. Applied to every module:
// composeApp pulls skiko-awt-runtime-all in transitively, and that is the
// artifact carrying the native library.
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.skiko") {
                useVersion("0.152.0-alpha02-nuvio1-SNAPSHOT")
            }
        }
        // The fat runtime is pinned by a strict constraint and carries the
        // stock native library for every platform. Drop it and let the
        // per-platform artifact below supply the patched one instead.
        exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
    }
}
