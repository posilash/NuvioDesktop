pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/cmp/dev") {
            mavenContent { includeGroupAndSubgroups("org.jetbrains.compose") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Compose dev builds and the Skiko they pin: 1.12.10-alpha01+dev4686 is
        // the first on skiko 0.152.x, whose Linux binary is the only one built
        // with Vulkan.
        maven("https://packages.jetbrains.team/maven/p/cmp/dev") {
            mavenContent {
                includeGroupAndSubgroups("org.jetbrains.compose")
                includeGroupAndSubgroups("org.jetbrains.skiko")
            }
        }
    }
}

include(":composeApp")
include(":androidApp")
include(":desktopSentry")
include(":waylandHost")
if (!System.getProperty("os.name").contains("win", ignoreCase = true)) {
    include(":composeMediaPlayer")
    project(":composeMediaPlayer").projectDir = file("vendor/compose-media-player/mediaplayer")
}
