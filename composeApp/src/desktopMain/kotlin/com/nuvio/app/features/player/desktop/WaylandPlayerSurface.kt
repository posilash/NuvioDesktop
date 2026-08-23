package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
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
            audioUrl = sourceAudioUrl,
            subtitles = externalSubtitles.map {
                WaylandVideoBridge.ExternalSubtitle(
                    url = it.url,
                    language = it.language,
                    title = it.name,
                )
            },
        )
        // The runtime tracks whether the resume position was actually applied
        // and re-requests it until told. open() passed it to mpv above.
        if (initialPositionRequestKey != null) {
            onInitialPositionHandled(initialPositionRequestKey, initialPositionMs > 0)
        }
        onDispose {
            WaylandVideoLog.log("surface: disposed")
            bridge.stop()
        }
    }

    LaunchedEffect(sourceUrl) {
        // Deliberately asynchronous, not in the DisposableEffect above: the
        // player runtime resets its controller from a source-keyed
        // LaunchedEffect, written for the stock surface whose controller
        // arrives only after native init. A controller handed over
        // synchronously in the commit phase lands *before* that reset and is
        // silently wiped -- keys and controls then no-op against a null
        // controller while playback runs fine. Runtime effects launch first
        // (they compose first), so delivering from a coroutine here orders
        // this after the reset.
        onControllerReady(WaylandPlayerController(bridge))
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
                    playbackSpeed = s.playbackSpeed,
                ),
            )
            if (s.error != null && s.error != lastError) {
                lastError = s.error
                onError(s.error)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(resizeMode) {
        bridge.setResizeMode(resizeMode)
    }

    Canvas(
        modifier
            .fillMaxSize()
            // The video is composited by the host, underneath the scene; the
            // draw below only punches the transparent hole it shows through.
            // Layout is what knows where that hole is. Input deliberately
            // lives at the screen level (PlayerScreenDesktopInput): gesture
            // overlays cover this node, so nothing pointed here would arrive.
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                bridge.setVideoRect(bounds.left, bounds.top, bounds.width, bounds.height)
            },
    ) {
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

    override fun getAudioTracks(): List<AudioTrack> = bridge.audioTracks()
    override fun getSubtitleTracks(): List<SubtitleTrack> = bridge.subtitleTracks()
    override fun selectAudioTrack(index: Int) = bridge.selectAudioTrack(index)
    override fun selectSubtitleTrack(index: Int) = bridge.selectSubtitleTrack(index)

    override fun setSubtitleUri(url: String) = bridge.setSubtitleUrl(url)
    override fun clearExternalSubtitle() = bridge.clearExternalSubtitles()
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        bridge.clearExternalSubtitles()
        bridge.selectSubtitleTrack(trackIndex)
    }

    // Subtitle styling comes from the user's own mpv.conf, which the host
    // loads; the app's style panel is not mapped onto it.
    override fun applySubtitleStyle(style: SubtitleStyleState, useLibass: Boolean) {}
    override fun setSubtitleDelayMs(delayMs: Int) = bridge.setSubtitleDelayMs(delayMs)
}
