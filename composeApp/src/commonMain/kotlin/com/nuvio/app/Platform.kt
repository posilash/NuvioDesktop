package com.nuvio.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean
internal expect val isWindows: Boolean

/**
 * True when the player's control chrome is drawn outside Compose.
 *
 * The desktop player normally puts its controls in a native WebKit overlay
 * above the video window, so the Compose chrome is deliberately not composed --
 * it would draw a second set. That reasoning is about *how the video is
 * presented*, not about the platform: a desktop host that renders video into
 * the Compose scene itself has no overlay, and needs the Compose chrome. Gating
 * on `isDesktop` conflated the two and left such a host with no controls at all.
 */
internal expect val usesNativePlayerChrome: Boolean

