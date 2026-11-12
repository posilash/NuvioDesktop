package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Back from the Wayland host, which feeds Backspace that nothing else wanted.
 * It goes through the same dispatcher as Esc and mouse Back, so open dialogs
 * still take it before screen-level handlers.
 */
object DesktopBackDispatcher {
    private val input = DirectNavigationEventInput()

    fun back() = input.backCompleted()

    @Composable
    fun Install() {
        val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        DisposableEffect(dispatcher) {
            dispatcher?.addInput(input)
            onDispose { dispatcher?.removeInput(input) }
        }
    }
}

// Registers with Compose's window back dispatcher, which already receives Esc and orders
// handlers against dialogs and NavDisplay the same way BackHandler does on Android.
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
