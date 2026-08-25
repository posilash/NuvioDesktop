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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
    playerControlsState: com.nuvio.app.features.player.PlayerControlsState,
    onPlayerControlsAction: (com.nuvio.app.features.player.PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
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

    // Read here, in the composable body, so the commit-phase effect below can
    // hand the chrome this session's state in the same breath as open().
    val fullscreenNow = com.nuvio.app.core.ui.isFullscreenActionActive()

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
        // Straight after open, in the same commit: the host's chrome cannot
        // show anything until it has this session's state, so every hop
        // between the two is time the player spends black. The structural
        // effect below re-pushes the identical payload a dispatch later,
        // which is a no-op for the page but keeps the dedup honest.
        if (WaylandVideoBridge.webChromeActive) {
            bridge.pushControlsJson(playerControlsState.withVolume(bridge).toControlsJson(fullscreenNow))
        }
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

    val controller = remember(bridge) { WaylandPlayerController(bridge) }
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
        onControllerReady(controller)
        var lastError: String? = null
        var lastPushed: PlayerPlaybackSnapshot? = null
        while (true) {
            val s = bridge.snapshot() // pure cache reads; never touches the core
            // Seek-hold: right after a seek, mpv still reports the old
            // position for a few hundred ms, which made the seek bar bounce
            // back before jumping to the target. Report the target until
            // playback catches up (or the hold times out).
            val seekTarget = controller.pendingSeekTargetMs
            val position = if (
                seekTarget >= 0 &&
                System.nanoTime() - controller.pendingSeekAtNs < 1_500_000_000L &&
                kotlin.math.abs(s.positionMs - seekTarget) > 800
            ) {
                seekTarget
            } else {
                if (seekTarget >= 0) controller.clearPendingSeek()
                s.positionMs
            }
            // Quantize what recomposition can see: with the chrome hidden,
            // sub-second position ticks still recomposed the whole player
            // screen 10x/s, and at ~20ms a scene render that is most of the
            // measured judder. Whole seconds recompose once per second; every
            // state the UI shows survives unchanged.
            val snapshot = PlayerPlaybackSnapshot(
                isLoading = s.isBuffering,
                isPlaying = s.isPlaying,
                isEnded = s.hasEnded,
                durationMs = s.durationMs,
                positionMs = (position / 1000) * 1000,
                bufferedPositionMs = (s.bufferedMs / 1000) * 1000,
                playbackSpeed = s.playbackSpeed,
                volumeLevel = s.volumeLevel,
            )
            if (snapshot != lastPushed) {
                lastPushed = snapshot
                onSnapshot(snapshot)
            }
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

    // The chrome's play/pause flows through shouldPlay -> playWhenReady, not
    // through a controller call -- same as stock, which drives the engine
    // from exactly this effect.
    //
    // Gated like upstream's `if (!sourceAvailable) return@LaunchedEffect`:
    // the runtime computes playWhenReady as `shouldPlay && currentSource !=
    // null`, so it also goes false while a source is being switched, with
    // the surface still composed against the retained one. Acting on that
    // paused the new file the moment it opened ("sometimes the video starts
    // paused"). Only the first composition for a given source is skipped;
    // real pause/play transitions after it still apply.
    LaunchedEffect(sourceUrl, playWhenReady) {
        // A false here can mean "user paused" OR "source momentarily
        // unavailable", because the runtime computes playWhenReady as
        // `shouldPlay && currentSource != null`. Upstream keeps those
        // separate and returns early when the source is not available; the
        // equivalent state here is the host still opening the file, and
        // acting on it paused the stream ~200ms after it started.
        if (!playWhenReady && bridge.isOpening()) return@LaunchedEffect
        if (playWhenReady) bridge.play() else bridge.pause()
    }

    if (WaylandVideoBridge.webChromeActive) {
        // Stock chrome mode: the page draws the controls; this side feeds it
        // state and routes its events with exactly the stock controller's
        // table (NativePlayerController.handlePlayerEvent).
        val isFullscreen = com.nuvio.app.core.ui.isFullscreenActionActive()
        // Push only on STRUCTURAL changes, exactly like stock's
        // NativeControlsStructureKey dedup: position/duration/loading tick
        // through playerUpdate instead. Re-pushing the whole controls JSON on
        // every position tick made the page rebuild its DOM once a second --
        // visible as flicker whenever the sources/episodes panes were open.
        // Stock order (NativePlayerController.updateControls): fill the level
        // first, then key off the filled state. Nothing in common code ever
        // sets volumeLevel -- it is the platform's to report -- so keying off
        // the raw state means the key never moves when the volume does, and
        // the page keeps whatever number it was first told.
        val stateWithVolume = playerControlsState.withVolume(bridge)
        val structureKey = stateWithVolume.nativeControlsStructureKey()
        LaunchedEffect(structureKey, isFullscreen) {
            bridge.pushControlsJson(stateWithVolume.toControlsJson(isFullscreen))
        }
        DisposableEffect(Unit) {
            WaylandVideoBridge.onChromeEvent = { type, value ->
                when (type) {
                    "scrubChange" -> {
                        if (!onPlayerControlsScrubChange(value.toLong())) Unit
                    }
                    "scrubFinish" -> {
                        if (!onPlayerControlsScrubFinished(value.toLong())) {
                            bridge.seekTo(value.toLong())
                        }
                    }
                    "toggleFullscreen" -> com.nuvio.app.core.ui.toggleFullscreenAction()
                    "volumeChange" ->
                        bridge.setVolumeFraction(
                            (if (value > 1.0) value / 100.0 else value).toFloat(),
                        )
                    "volumeChangeTemporary" ->
                        bridge.setVolumeFraction(
                            (if (value > 1.0) value / 100.0 else value).toFloat(),
                        )
                    else -> {
                        // Stock's full chain (handlePlayerEvent): the raw
                        // event first, then the PlayerControlsAction mapping,
                        // then the engine-level fallback. Routing only the
                        // first leg silently dropped every action-mapped
                        // button on the chrome (play/pause, skip, panes...).
                        if (!onPlayerControlsEvent(type, value)) {
                            val action = type.toPlayerControlsAction()
                            if (action != null && !onPlayerControlsAction(action)) {
                                handleWaylandFallbackAction(action, bridge)
                            }
                        }
                    }
                }
            }
            onDispose { WaylandVideoBridge.onChromeEvent = null }
        }
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

    // Seek-hold state, read by the surface's snapshot loop; see there.
    @Volatile var pendingSeekTargetMs: Long = -1L
        private set
    @Volatile var pendingSeekAtNs: Long = 0L
        private set

    fun clearPendingSeek() { pendingSeekTargetMs = -1L }

    override fun play() = bridge.play()
    override fun pause() = bridge.pause()
    override fun seekTo(positionMs: Long) {
        pendingSeekTargetMs = positionMs
        pendingSeekAtNs = System.nanoTime()
        bridge.seekTo(positionMs)
    }
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

    // Position -> mpv id resolution, exactly as the stock controller does it
    // (resolveTrackId): the UI's index is a list position; only a resolved id
    // reaches mpv, and a stale or unknown position is a no-op rather than a
    // deselection. Negative still means "off", passed straight through.
    override fun selectAudioTrack(index: Int) {
        if (index < 0) return bridge.selectAudioTrack(-1)
        val id = bridge.audioTracks().getOrNull(index)?.id?.toIntOrNull() ?: return
        bridge.selectAudioTrack(id)
    }

    override fun selectSubtitleTrack(index: Int) {
        if (index < 0) return bridge.selectSubtitleTrack(-1)
        val id = bridge.subtitleTracks().getOrNull(index)?.id?.toIntOrNull() ?: return
        bridge.selectSubtitleTrack(id)
    }

    override fun setSubtitleUri(url: String) = bridge.setSubtitleUrl(url)
    override fun clearExternalSubtitle() = bridge.clearExternalSubtitles()
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        bridge.clearExternalSubtitles()
        selectSubtitleTrack(trackIndex)
    }

    // Subtitle styling comes from the user's own mpv.conf, which the host
    // loads; the app's style panel is not mapped onto it.
    override fun applySubtitleStyle(style: SubtitleStyleState, useLibass: Boolean) {}
    override fun setSubtitleDelayMs(delayMs: Int) = bridge.setSubtitleDelayMs(delayMs)
}

/** Stock's null-volume fill, against the Wayland bridge instead of the handle. */
private fun com.nuvio.app.features.player.PlayerControlsState.withVolume(
    bridge: WaylandVideoBridge.Delegate,
): com.nuvio.app.features.player.PlayerControlsState =
    if (volumeLevel == null) {
        copy(volumeLevel = (bridge.snapshot().volumeLevel ?: 1f).coerceIn(0f, 1f))
    } else {
        this
    }

/**
 * Stock handleFallbackAction, expressed against the Wayland bridge: the
 * last-resort behaviours the engine owes the chrome when neither the screen's
 * event handler nor its action handler claims an action.
 */
private fun handleWaylandFallbackAction(
    action: com.nuvio.app.features.player.PlayerControlsAction,
    bridge: WaylandVideoBridge.Delegate,
) {
    when (action) {
        com.nuvio.app.features.player.PlayerControlsAction.TogglePlayback,
        com.nuvio.app.features.player.PlayerControlsAction.KeyboardTogglePlayback -> {
            val s = bridge.snapshot()
            when {
                s.hasEnded -> {
                    bridge.seekTo(0L)
                    bridge.play()
                }
                s.isPlaying -> bridge.pause()
                else -> bridge.play()
            }
        }
        com.nuvio.app.features.player.PlayerControlsAction.SeekBack,
        com.nuvio.app.features.player.PlayerControlsAction.KeyboardSeekBack ->
            bridge.seekBy(-10_000L)
        com.nuvio.app.features.player.PlayerControlsAction.SeekForward,
        com.nuvio.app.features.player.PlayerControlsAction.KeyboardSeekForward ->
            bridge.seekBy(10_000L)
        com.nuvio.app.features.player.PlayerControlsAction.KeyboardVolumeDown ->
            bridge.setVolumeFraction(((bridge.snapshot().volumeLevel ?: 1f) - 0.05f).coerceIn(0f, 1f))
        com.nuvio.app.features.player.PlayerControlsAction.KeyboardVolumeUp ->
            bridge.setVolumeFraction(((bridge.snapshot().volumeLevel ?: 1f) + 0.05f).coerceIn(0f, 1f))
        else -> Unit
    }
}
