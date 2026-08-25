package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Desktop back, for hosts that have a key to spend on it.
 *
 * Android gets a system back button and the screens register for it through
 * [PlatformBackHandler]; desktop had nowhere to route one, so this was a no-op
 * and every registration went nowhere. Keeping the registrations and giving
 * them a dispatcher means the Wayland host can raise back without any screen
 * knowing a key was involved.
 */
object DesktopBackDispatcher {
    private val handlers = ArrayDeque<() -> Unit>()

    /** True when something took it; false leaves the host free to do nothing. */
    fun back(): Boolean {
        val handler = synchronized(handlers) { handlers.lastOrNull() } ?: return false
        handler()
        return true
    }

    internal fun register(handler: () -> Unit): () -> Unit {
        synchronized(handlers) { handlers.addLast(handler) }
        return { synchronized(handlers) { handlers.remove(handler) } }
    }
}

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // Read through so a handler registered once still calls the newest lambda.
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // Effects run innermost-last, so the deepest screen is on top of the
        // stack -- an open modal takes back before the screen behind it.
        val unregister = DesktopBackDispatcher.register { current() }
        onDispose { unregister() }
    }
}
