package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/** Back from the Wayland host, which cannot see the internal dispatcher. */
object DesktopBackDispatcher {
    /** True when something took it; false leaves the host free to do nothing. */
    fun back(): Boolean = PlatformBackDispatcher.dispatch()
}

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val registration = remember { PlatformBackRegistration(enabled, onBack) }
    SideEffect {
        registration.enabled = enabled
        registration.onBack = onBack
    }
    DisposableEffect(registration) {
        PlatformBackDispatcher.register(registration)
        onDispose { PlatformBackDispatcher.unregister(registration) }
    }
}
