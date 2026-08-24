package com.nuvio.wayland

import kotlin.system.exitProcess

/**
 * Standalone proof of the Vulkan render path, run via `gradle :waylandHost:vkSmoke`.
 *
 * Headless on purpose: no window, no GL, no compositor. What it verifies is
 * exactly the producer half of the future VK->GL bridge:
 *
 *   - the fork's "vulkan" render context comes up against a host-owned device
 *   - frames actually render (mpv paces, we count)
 *   - hwdec-current is "nvdec" -- zero-copy, not nvdec-copy, which is what
 *     the whole exercise exists for
 *   - every buffer's memory and semaphore exported as fds a GL consumer could
 *     import, with out_layout reported for the eventual glWaitSemaphoreEXT
 */
fun main() {
    val libmpv = System.getProperty(
        "nuvio.wayland.libmpv", "/home/annihilator/dev/mpv/build/libmpv.so.2",
    )
    val media = System.getProperty("nuvio.wayland.media", "/tmp/nuvio-testclip5m.mp4")
    val hwdec = System.getProperty("nuvio.wayland.hwdec", "auto")

    check(Mpv.load(libmpv)) { "failed to load $libmpv" }
    println("[vksmoke] libmpv: $libmpv")
    println("[vksmoke] media: $media")

    val mpv = Mpv.create()
    mpv.setOption("config", "no")
    mpv.setOption("terminal", "yes")
    mpv.setOption("msg-level", System.getProperty("nuvio.wayland.videoLog") ?: "all=info")
    mpv.setOption("vo", "libmpv")
    // Audio would drag in a sound server this headless harness has no use
    // for; hwdec selection is entirely a video-chain affair.
    mpv.setOption("audio", "no")
    mpv.setOption("hwdec", hwdec)
    mpv.initialize()
    mpv.observeProperty("hwdec-current")
    mpv.startEventLoop(null)

    val pipeline = VideoPipelineVk(mpv)
    pipeline.setTargetSize(1920, 1080)
    pipeline.start()
    try {
        pipeline.awaitReady()
    } catch (t: Throwable) {
        println("[vksmoke] render context creation: FAILED")
        t.printStackTrace()
        exitProcess(1)
    }
    println("[vksmoke] render context creation: OK (device=${pipeline.deviceName})")

    mpv.command("loadfile", media)

    val seconds = 10
    for (i in 1..seconds) {
        Thread.sleep(1000)
        println(
            "[vksmoke] t=${i}s " + pipeline.report(1.0) +
                " hwdec-current=" + (mpv.cachedString("hwdec-current") ?: "<unset>"),
        )
    }

    val frames = pipeline.totalRenders
    val hwdecCurrent = mpv.getProperty("hwdec-current") ?: ""
    val exports = pipeline.exportSnapshot()

    println("[vksmoke] frames rendered: $frames")
    println("[vksmoke] hwdec-current: ${hwdecCurrent.ifEmpty { "<empty>" }}")
    for (e in exports) {
        println(
            "[vksmoke] buffer[${e.index}] gen=${e.generation} ${e.width}x${e.height}" +
                " memoryFd=${e.memoryFd} allocationSize=${e.allocationSize}" +
                " semaphoreFd=${e.semaphoreFd} out_layout=${e.outLayout}",
        )
    }

    pipeline.stop()
    mpv.quitAndAwaitShutdown()
    mpv.close()

    // Only two of the three buffers allocate here: with no consumer holding a
    // `displayed` buffer, the rotation is publish/render ping-pong -- the
    // third exists for the consumer the real host will have.
    val allocated = exports.filter { it.generation > 0 }
    val exportsOk = allocated.size >= 2 && allocated.all {
        it.memoryFd >= 0 && it.semaphoreFd >= 0 && it.allocationSize > 0 && it.outLayout != 0
    }
    val pass = frames > 0 && hwdecCurrent == "nvdec" && exportsOk
    println(
        "VKSMOKE: " + if (pass) "PASS" else {
            "FAIL (frames=$frames hwdec-current=${hwdecCurrent.ifEmpty { "<empty>" }} exportsOk=$exportsOk)"
        },
    )
    exitProcess(if (pass) 0 else 1)
}
