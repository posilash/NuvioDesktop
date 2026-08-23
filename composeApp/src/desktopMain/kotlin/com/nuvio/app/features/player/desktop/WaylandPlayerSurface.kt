package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
 * Draws nothing. The host has already painted the current video frame into the
 * framebuffer before Compose runs, so this composable's only jobs are to drive
 * playback through [WaylandVideoBridge] and to report state back to the player
 * UI. That is the whole point: with no component to embed there is no
 * `SwingPanel`, and therefore no AWT.
 */
@Composable
internal fun WaylandPlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long,
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

    // The frame is drawn here, inside the scene, so it is ordered and clipped
    // like any other Compose content.
    // Compose only redraws what has been invalidated. The video texture
    // changes outside the composition entirely, so without a per-frame tick
    // the Canvas below is drawn once and then never again -- a still image
    // over a playing stream.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            tick++
        }
    }

    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") tick // read it: this is what forces the redraw
        if (WaylandVideoLog.enabled) WaylandVideoLog.noteDraw(size.width, size.height)
        drawIntoCanvas { canvas ->
            bridge.drawVideo(canvas.nativeCanvas, size.width, size.height)
        }
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
