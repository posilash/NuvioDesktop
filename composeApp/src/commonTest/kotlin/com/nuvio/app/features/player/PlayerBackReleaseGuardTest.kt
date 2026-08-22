package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackReleaseGuardTest {
    @Test
    fun asynchronousReleaseFailureAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var failRelease: ((String) -> Unit)? = null
        var releases = 0

        guard.request(
            canStart = { true },
            releaseBeforeBack = { _, onFailed ->
                releases += 1
                failRelease = onFailed
            },
            beforePop = {},
            pop = { true },
        )
        failRelease?.invoke("timed out")
        guard.request(
            canStart = { true },
            releaseBeforeBack = { _, _ -> releases += 1 },
            beforePop = {},
            pop = { true },
        )

        assertEquals(2, releases)
    }

    @Test
    fun synchronousReleaseFailureAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var attempts = 0
        var pops = 0

        guard.request(
            canStart = { true },
            releaseBeforeBack = { onReleased, _ ->
                attempts += 1
                if (attempts == 1) error("release failed")
                onReleased()
            },
            beforePop = {},
            pop = { pops += 1; true },
        )
        guard.request(
            canStart = { true },
            releaseBeforeBack = { onReleased, _ -> attempts += 1; onReleased() },
            beforePop = {},
            pop = { pops += 1; true },
        )

        assertEquals(2, attempts)
        assertEquals(1, pops)
    }

    @Test
    fun completionFailureAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var beforePopAttempts = 0
        var pops = 0

        repeat(2) {
            guard.request(
                canStart = { true },
                releaseBeforeBack = { onReleased, _ -> onReleased() },
                beforePop = {
                    beforePopAttempts += 1
                    if (beforePopAttempts == 1) error("before pop failed")
                },
                pop = { pops += 1; true },
            )
        }

        assertEquals(2, beforePopAttempts)
        assertEquals(1, pops)
    }
}
