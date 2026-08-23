package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    val keepAwakeController = remember { DesktopKeepAwakeController() }

    SideEffect {
        keepAwakeController.setEnabled(keepScreenAwake)
    }

    DisposableEffect(keepAwakeController) {
        onDispose {
            keepAwakeController.close()
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? {
    // The Wayland host owns playback through mpv, whose volume/mute
    // properties back the player's volume machinery -- feedback overlay,
    // scroll and key bindings. The stock desktop path keeps its overlay's own
    // volume UI and needs no controller here. Brightness stays unsupported:
    // a desktop compositor's display brightness is not the player's to move.
    val bridge = com.nuvio.app.features.player.desktop.WaylandVideoBridge.delegate ?: return null
    return remember(bridge) {
        object : PlayerGestureController {
            override fun currentBrightness(): Float? = null
            override fun setBrightness(level: Float): Float? = null
            override fun currentVolume(): PlayerAudioLevel = bridge.audioLevel()
            override fun setVolume(level: Float): PlayerAudioLevel {
                bridge.setVolumeFraction(level)
                return bridge.audioLevel()
            }
        }
    }
}

private class DesktopKeepAwakeController : AutoCloseable {
    private var caffeinateProcess: Process? = null
    private var windowsDisplaySleepInhibited = false

    fun setEnabled(enabled: Boolean) {
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> {
                if (enabled) {
                    startCaffeinate()
                } else {
                    stopCaffeinate()
                }
            }

            DesktopHostOs.WINDOWS -> setWindowsDisplaySleepInhibited(enabled)
            DesktopHostOs.LINUX, DesktopHostOs.UNKNOWN -> Unit
        }
    }

    private fun startCaffeinate() {
        if (caffeinateProcess?.isAlive == true) return

        val currentPid = ProcessHandle.current().pid().toString()
        caffeinateProcess = runCatching {
            ProcessBuilder(
                "/usr/bin/caffeinate",
                "-d",
                "-i",
                "-w",
                currentPid,
            ).start()
        }.getOrNull()
    }

    private fun stopCaffeinate() {
        caffeinateProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        caffeinateProcess = null
    }

    private fun setWindowsDisplaySleepInhibited(inhibited: Boolean) {
        if (windowsDisplaySleepInhibited == inhibited) return

        val applied = runCatching {
            NativePlayerBridge.setWindowsDisplaySleepInhibited(inhibited)
        }.getOrDefault(false)
        if (applied) {
            windowsDisplaySleepInhibited = inhibited
        }
    }

    override fun close() {
        stopCaffeinate()
        setWindowsDisplaySleepInhibited(false)
    }
}
