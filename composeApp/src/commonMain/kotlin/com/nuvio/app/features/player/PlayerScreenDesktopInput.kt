package com.nuvio.app.features.player

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.nuvio.app.isDesktop
import com.nuvio.app.usesNativePlayerChrome

/**
 * Desktop input behaviours for the player screen, when its chrome is Compose.
 *
 * The stock desktop build implements these in its web overlay's JS: moving
 * the mouse reveals the controls, and the keyboard drives playback. With
 * Compose chrome they belong here, on the screen's root -- an ancestor sees
 * pointer events for every descendant hit and key events bubbling from
 * whatever holds focus, so this works regardless of which gesture layers or
 * controls sit on top of the video surface. (A first attempt hung them on the
 * video surface itself; the screen's gesture overlays kept it out of the hit
 * path entirely.)
 */
@Composable
internal fun PlayerScreenRuntime.desktopPlayerInput(modifier: Modifier): Modifier {
    if (!isDesktop || usesNativePlayerChrome) return modifier

    // Key events route from the focused node upward; without focus anywhere
    // in the screen they go nowhere at all.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val gestureController = rememberPlayerGestureController()
    fun adjustVolume(delta: Float) {
        val controller = gestureController ?: return
        val current = controller.currentVolume()?.fraction ?: return
        val level = controller.setVolume((current + delta).coerceIn(0f, 1f)) ?: return
        showVolumeFeedback(level)
        controlsActivityTick += 1
    }

    return modifier
        .pointerInput(Unit) {
            // kotlin.time, not System.nanoTime(): this file is common code.
            var lastActivity: kotlin.time.TimeMark? = null
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        // Wheel over the video adjusts volume, matching the
                        // stock desktop behaviour -- but only wheel input that
                        // nothing else wanted. An earlier revision observed
                        // the Initial pass, which also sees every scroll made
                        // *inside* the episodes and sources panels: browsing a
                        // list silently wound the volume to zero, reported as
                        // "audio disappeared". The Final pass sees the event
                        // after the hit path has had it; a panel's scrollable
                        // consumes its own wheel, and consumed deltas are
                        // zeroed, so this now reacts only to scrolls over
                        // bare video.
                        val change = awaitPointerEvent(PointerEventPass.Final)
                            .changes.firstOrNull()
                        val notches = if (change != null && !change.isConsumed) {
                            change.scrollDelta.y
                        } else {
                            0f
                        }
                        if (notches != 0f) adjustVolume(-notches * 0.05f)
                    }
                    if (event.type == PointerEventType.Move) {
                        val stale = lastActivity
                            ?.let { it.elapsedNow().inWholeMilliseconds > 200 }
                            ?: true
                        // Same semantics as the cursorActivity controls event:
                        // reveal, and reset the auto-hide timer. Throttled far
                        // below that timeout.
                        if (stale && !playerControlsLocked) {
                            lastActivity = kotlin.time.TimeSource.Monotonic.markNow()
                            controlsVisible = true
                            controlsActivityTick += 1
                        }
                    }
                }
            }
        }
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            // Bubble phase: a focused text field consumes what it needs first,
            // and the modal flags guard the rest, so typing in a search box
            // never drives playback.
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (showSubtitleModal || showAudioModal || showVideoSettingsModal) {
                return@onKeyEvent false
            }
            when (event.key) {
                Key.Spacebar, Key.K -> {
                    togglePlayback()
                    controlsVisible = true
                    controlsActivityTick += 1
                    true
                }
                Key.DirectionLeft, Key.J -> {
                    seekBy(-10_000L)
                    controlsVisible = true
                    controlsActivityTick += 1
                    true
                }
                Key.DirectionRight, Key.L -> {
                    seekBy(10_000L)
                    controlsVisible = true
                    controlsActivityTick += 1
                    true
                }
                Key.DirectionUp -> {
                    adjustVolume(0.05f)
                    true
                }
                Key.DirectionDown -> {
                    adjustVolume(-0.05f)
                    true
                }
                Key.F -> {
                    if (com.nuvio.app.core.ui.isFullscreenActionSupported) {
                        com.nuvio.app.core.ui.toggleFullscreenAction()
                        true
                    } else {
                        false
                    }
                }
                Key.M -> {
                    // Mute toggle, mirroring mpv's own binding. Current state
                    // comes from the controller, not local bookkeeping.
                    val level = gestureController?.currentVolume()
                    if (level != null) {
                        playerController?.setMuted(!level.isMuted)
                        showVolumeFeedback(PlayerAudioLevel(level.fraction, !level.isMuted))
                        controlsActivityTick += 1
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
}
