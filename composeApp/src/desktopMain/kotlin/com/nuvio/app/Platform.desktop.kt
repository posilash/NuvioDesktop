package com.nuvio.app

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
internal actual val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

// Not a constant: the Wayland host installs its video bridge before the app is
// composed, and when it does the video lives inside the Compose scene with no
// native overlay above it, so Compose has to draw the chrome itself.
internal actual val usesNativePlayerChrome: Boolean
    get() = !com.nuvio.app.features.player.desktop.WaylandVideoBridge.isAvailable

