package com.nuvio.app.features.player

internal fun dispatchNavigationBack(
    isPlayerRoute: Boolean,
    playerBack: (() -> Unit)?,
    pop: () -> Unit,
) {
    if (isPlayerRoute) {
        playerBack?.invoke()
    } else {
        pop()
    }
}

internal class PlayerBackReleaseGuard {
    private var inFlight: Boolean = false

    fun request(
        canStart: () -> Boolean,
        releaseBeforeBack: (
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) -> Unit,
        beforePop: () -> Unit,
        pop: () -> Boolean,
    ) {
        if (inFlight) return
        val allowed = try {
            canStart()
        } catch (_: Exception) {
            false
        }
        if (!allowed) return

        inFlight = true
        val complete = {
            try {
                beforePop()
                if (!pop()) inFlight = false
            } catch (_: Exception) {
                inFlight = false
            }
        }
        try {
            releaseBeforeBack(complete) { inFlight = false }
        } catch (_: Exception) {
            inFlight = false
        }
    }
}
