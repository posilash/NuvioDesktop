package com.nuvio.app.core.build

private val isWindowsDesktop = System.getProperty("os.name")
    ?.startsWith("Windows", ignoreCase = true)
    ?: false

// In-app trailers need a SwingPanel, which cannot exist without AWT.
private val isWaylandHost: Boolean
    get() = com.nuvio.app.features.player.desktop.WaylandVideoBridge.isAvailable

actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = true
    actual val downloadsEnabled: Boolean = true
    actual val notificationsEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = true
    actual val donationActionsEnabled: Boolean = true
    actual val donationProgressEnabled: Boolean = false
    actual val accountDeletionEnabled: Boolean = false
    actual val personalMediaAddonCopyEnabled: Boolean = false
    actual val p2pEnabled: Boolean = true
    actual val externalPlayerSupported: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode
        get() = if (isWindowsDesktop || isWaylandHost) {
            TrailerPlaybackMode.EXTERNAL
        } else {
            TrailerPlaybackMode.IN_APP
        }
    actual val heroTrailerPlaybackSupported: Boolean
        get() = !(isWindowsDesktop || isWaylandHost)
    actual val inAppUpdaterEnabled: Boolean = true
    actual val imdbRatingLogoEnabled: Boolean = true
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = false
    actual val customServerConnectionsEnabled: Boolean = true
}
