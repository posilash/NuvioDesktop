package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import kotlinx.coroutines.delay

/**
 * Player surface for hosts that render video themselves, underneath Compose.
 *
 * Draws no video. The host composites the actual frames beneath the scene at
 * its own cadence; this composable reports where the video belongs, punches
 * the transparent hole it shows through, drives playback through
 * [WaylandVideoBridge], and reports state back to the player UI. With no
 * component to embed there is no `SwingPanel`, and therefore no AWT.
 */
@Composable
internal fun WaylandPlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val bridge = WaylandVideoBridge.delegate

    if (bridge == null) {
        // Should not happen: PlatformPlayerSurface only routes here when a
        // delegate is installed. Fail loudly rather than showing a black box.
        LaunchedEffect(Unit) { onError("No Wayland video host is installed") }
        return
    }

    DisposableEffect(sourceUrl) {
        WaylandVideoLog.log("surface: open url=$sourceUrl playWhenReady=$playWhenReady pos=$initialPositionMs")
        bridge.open(
            url = sourceUrl,
            headers = sourceHeaders.map { (k, v) -> "$k: $v" },
            startPositionMs = initialPositionMs,
            playWhenReady = playWhenReady,
        )
        onControllerReady(WaylandPlayerController(bridge))
        onDispose {
            WaylandVideoLog.log("surface: disposed")
            bridge.stop()
        }
    }

    LaunchedEffect(sourceUrl) {
        var lastError: String? = null
        while (true) {
            val s = bridge.snapshot()
            onSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = s.isBuffering,
                    isPlaying = s.isPlaying,
                    isEnded = s.hasEnded,
                    durationMs = s.durationMs,
                    positionMs = s.positionMs,
                    bufferedPositionMs = s.bufferedMs,
                ),
            )
            if (s.error != null && s.error != lastError) {
                lastError = s.error
                onError(s.error)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    // Desktop affordances the stock build gets from its web overlay: moving
    // the mouse reveals the controls, and the keyboard drives playback. The
    // runtime's cursorActivity event both shows the chrome and resets its
    // auto-hide timer, so throttling to well under that timeout loses nothing.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(sourceUrl) {
        // Keys route to the focused node; nothing else on this screen wants
        // focus, so the surface takes it when playback starts.
        runCatching { focusRequester.requestFocus() }
    }

    Canvas(
        modifier
            .fillMaxSize()
            // The video is composited by the host, underneath the scene; the
            // draw below only punches the transparent hole it shows through.
            // Layout is what knows where that hole is.
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                bridge.setVideoRect(bounds.left, bounds.top, bounds.width, bounds.height)
            }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event -> handlePlayerKey(event, bridge, onPlayerControlsEvent) }
            .pointerInput(Unit) {
                var lastActivityNs = 0L
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) {
                            val now = System.nanoTime()
                            if (now - lastActivityNs > 200_000_000L) {
                                lastActivityNs = now
                                onPlayerControlsEvent("cursorActivity", 0.0)
                            }
                        }
                    }
                }
            },
    ) {
        if (WaylandVideoLog.enabled) WaylandVideoLog.noteDraw(size.width, size.height)
        drawIntoCanvas { canvas ->
            bridge.drawVideo(canvas.nativeCanvas, size.width, size.height)
        }
    }
}

/**
 * The keyboard bindings the stock desktop build implements in its web
 * overlay's JS, expressed against the bridge directly.
 */
private fun handlePlayerKey(
    event: KeyEvent,
    bridge: WaylandVideoBridge.Delegate,
    onPlayerControlsEvent: (String, Double) -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Spacebar, Key.K -> {
            if (bridge.snapshot().isPlaying) bridge.pause() else bridge.play()
            onPlayerControlsEvent("cursorActivity", 0.0)
            true
        }
        Key.DirectionLeft, Key.J -> {
            bridge.seekBy(-10_000L)
            onPlayerControlsEvent("cursorActivity", 0.0)
            true
        }
        Key.DirectionRight, Key.L -> {
            bridge.seekBy(10_000L)
            onPlayerControlsEvent("cursorActivity", 0.0)
            true
        }
        else -> false
    }
}

private const val POLL_INTERVAL_MS = 100L

private class WaylandPlayerController(
    private val bridge: WaylandVideoBridge.Delegate,
) : PlayerEngineController {

    override fun play() = bridge.play()
    override fun pause() = bridge.pause()
    override fun seekTo(positionMs: Long) = bridge.seekTo(positionMs)
    override fun seekBy(offsetMs: Long) = bridge.seekBy(offsetMs)
    override fun setPlaybackSpeed(speed: Float) = bridge.setSpeed(speed)
    override fun setMuted(muted: Boolean) = bridge.setMuted(muted)

    override fun retry() {
        // mpv recovers from transient stream errors on its own; a seek to the
        // current position is the cheapest nudge that forces a re-read.
        bridge.seekBy(0)
    }

    // Track enumeration is not wired yet; the UI treats an empty list as
    // "nothing to choose from" rather than erroring.
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun selectAudioTrack(index: Int) = bridge.selectAudioTrack(index)
    override fun selectSubtitleTrack(index: Int) = bridge.selectSubtitleTrack(index)

    override fun setSubtitleUri(url: String) = bridge.setSubtitleUrl(url)
    override fun clearExternalSubtitle() = bridge.clearExternalSubtitles()
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        bridge.clearExternalSubtitles()
        bridge.selectSubtitleTrack(trackIndex)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState, useLibass: Boolean) {}
    override fun setSubtitleDelayMs(delayMs: Int) {}
}
