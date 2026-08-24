package com.nuvio.wayland

import org.lwjgl.opengl.EXTMemoryObject
import org.lwjgl.opengl.EXTMemoryObjectFD
import org.lwjgl.opengl.EXTSemaphore
import org.lwjgl.opengl.EXTSemaphoreFD
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL32
import org.lwjgl.opengl.GL45

/**
 * Adapts [VideoPipelineVk] to the GL scene: mpv renders with Vulkan
 * (zero-copy nvdec, the same API the user's reference build runs), and each
 * exported VkImage is imported into this GL context once per generation via
 * GL_EXT_memory_object_fd. Per fresh frame the GL-side cost is one
 * glWaitSemaphoreEXT (render-done: orders sampling after mpv's write) and
 * one glSignalSemaphoreEXT (glDone, on the buffer being replaced: orders
 * mpv's next write after this context's reads) -- the driver-level fences
 * that replace the GL pipeline's fence handoff. Everything downstream (Skia
 * FBO wrap, composite) is unchanged.
 *
 * Must be constructed and used on the thread that owns the window GL context
 * (the EDT): imports and waits are context operations.
 */
class VkGlDisplayPipeline(private val vk: VideoPipelineVk) : DisplayPipeline {
    // GL_LAYOUT_GENERAL_EXT: the layout mpv leaves images in (out_layout=2 =
    // VK_IMAGE_LAYOUT_GENERAL). The wait needs it so the driver can insert
    // the right transition.
    private val glLayoutGeneral = 0x958D

    private class Imported(
        val generation: Int,
        val memoryObject: Int,
        val texture: Int,
        val semaphore: Int,
        /** GL twin of [VideoPipelineVk.Buffer.glDoneSemaphore]: signaled here
         * when this texture stops being the displayed frame, waited VK-side
         * before mpv writes into the image again. */
        val glDoneSemaphore: Int,
    )

    private val imports = HashMap<Int, Imported>()

    override var probe: Boolean = false
    override var onFrame: (() -> Unit)?
        get() = vk.onFrame
        set(v) { vk.onFrame = v }
    // 24fps default; the scheduler only uses this as a scene-defer hint.
    override val publishIntervalMs: Double get() = 41.7

    override fun setTargetSize(width: Int, height: Int) = vk.setTargetSize(width, height)
    override fun start() = vk.start()
    override fun stop() = vk.stop()
    override fun awaitReady() = vk.awaitReady()
    override fun report(elapsedSeconds: Double): String = vk.report(elapsedSeconds)

    private fun importBuffer(b: VideoPipelineVk.Buffer): Imported {
        // The fds are consumed by the GL driver on import; flag the pipeline
        // so it does not close handles it no longer owns. Ownership is
        // recorded per fd, immediately after the call that consumes it: if it
        // were flagged only at the end and anything in between threw, the
        // pipeline would later close() an fd the driver already owns -- and
        // since fd numbers get recycled fast (mpv opens fds for every decoded
        // frame), that close lands on a stranger's handle. Fd double-close is
        // exactly the deferred native corruption class behind the hs_err
        // SIGSEGVs.
        val memObj = EXTMemoryObject.glCreateMemoryObjectsEXT()
        EXTMemoryObjectFD.glImportMemoryFdEXT(
            memObj, b.allocationSize,
            EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, b.memoryFd,
        )
        b.memoryFd = -1 // consumed above; never ours to close again
        val tex = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        // The image was created TILING_OPTIMAL; the import must say so or
        // the texture reads garbage.
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D,
            EXTMemoryObject.GL_TEXTURE_TILING_EXT,
            EXTMemoryObject.GL_OPTIMAL_TILING_EXT,
        )
        EXTMemoryObject.glTexStorageMem2DEXT(
            GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, b.width, b.height, memObj, 0L,
        )
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        val sem = EXTSemaphore.glGenSemaphoresEXT()
        EXTSemaphoreFD.glImportSemaphoreFdEXT(
            sem, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, b.semaphoreFd,
        )
        b.semaphoreFd = -1 // consumed above
        val glDone = EXTSemaphore.glGenSemaphoresEXT()
        EXTSemaphoreFD.glImportSemaphoreFdEXT(
            glDone, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, b.glDoneSemaphoreFd,
        )
        b.glDoneSemaphoreFd = -1 // consumed above
        b.fdsOwnedByConsumer = true
        return Imported(b.generation, memObj, tex, sem, glDone)
    }

    /** Stale imports awaiting GPU retirement before their GL objects die. */
    private class PendingDelete(val imported: Imported, val fence: Long)
    private val pendingDeletes = ArrayList<PendingDelete>()

    private fun evictStale(liveGenerations: Set<Int>) {
        val stale = imports.keys.filter { it !in liveGenerations }
        for (g in stale) {
            val imp = imports.remove(g) ?: continue
            // Deleting immediately is a use-after-free by proxy: the GL
            // command stream may still hold last frame's composite sampling
            // this texture, or an unexecuted glWaitSemaphoreEXT, and the
            // memory object is the last thing keeping the (VK-side already
            // freed) allocation alive. The driver crashes later, off-thread
            // -- the hs_err SIGSEGVs in libnvidia-eglcore. So: fence the
            // stream now, delete only once the fence has retired.
            pendingDeletes += PendingDelete(
                imp, GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0),
            )
        }
        collectRetiredDeletes()
    }

    /** Non-blocking sweep; runs per acquire while anything is pending. */
    private fun collectRetiredDeletes() {
        if (pendingDeletes.isEmpty()) return
        val it = pendingDeletes.iterator()
        while (it.hasNext()) {
            val p = it.next()
            // FLUSH_COMMANDS ensures the fence is not stuck behind an
            // unflushed tail of the stream; timeout 0 keeps this a poll.
            val s = GL32.glClientWaitSync(p.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L)
            if (s == GL32.GL_ALREADY_SIGNALED || s == GL32.GL_CONDITION_SATISFIED) {
                GL32.glDeleteSync(p.fence)
                GL11.glDeleteTextures(p.imported.texture)
                EXTMemoryObject.glDeleteMemoryObjectsEXT(p.imported.memoryObject)
                EXTSemaphore.glDeleteSemaphoresEXT(p.imported.semaphore)
                EXTSemaphore.glDeleteSemaphoresEXT(p.imported.glDoneSemaphore)
                it.remove()
            }
        }
    }

    override fun acquireFrame(): DisplayPipeline.Frame? {
        val f = vk.acquireDisplayFrame() ?: return null
        val b = f.buffer
        val imp = imports.getOrPut(b.generation) {
            evictStale(vk.exportSnapshot().map { it.generation }.toSet())
            importBuffer(b)
        }
        collectRetiredDeletes()
        if (f.fresh) {
            // The buffer this frame replaces owes the render thread a glDone
            // signal before mpv may write into it again. Every sampling of
            // that texture was issued in earlier composites, so a signal
            // inserted now is ordered after those reads in this context's
            // command stream. The flush is load-bearing: the Vulkan side
            // enqueues a GPU wait on the strength of notifyGlDone, and a
            // wait whose signal never reached a queue would hang the device.
            f.retired?.let { prev ->
                imports[prev.generation]?.let { prevImp ->
                    EXTSemaphore.glSignalSemaphoreEXT(
                        prevImp.glDoneSemaphore, intArrayOf(),
                        intArrayOf(prevImp.texture), intArrayOf(glLayoutGeneral),
                    )
                    GL45.glFlush()
                    vk.notifyGlDone(prev)
                }
                // No import (cannot normally happen for a displayed buffer):
                // leave glDoneOwed unset -- the buffer stays parked rather
                // than risking a wait with no signal.
            }
            // Inherit the render-done wait from the Vulkan side. The GL
            // driver orders subsequent sampling of the texture after mpv's
            // queue signal, layouts handled by the transition hint.
            GL45.glFlush() // pending Skia work first; the wait is a queue op
            EXTSemaphore.glWaitSemaphoreEXT(
                imp.semaphore, intArrayOf(), intArrayOf(imp.texture),
                intArrayOf(glLayoutGeneral),
            )
        }
        return DisplayPipeline.Frame(imp.texture, b.width, b.height, b.generation, f.fresh)
    }
}
