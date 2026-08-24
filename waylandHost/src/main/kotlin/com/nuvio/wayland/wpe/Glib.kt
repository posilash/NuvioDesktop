package com.nuvio.wayland.wpe

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The minimum of GLib needed to host WPE WebKit: a main loop on a dedicated
 * thread, and a way to run code on it. WebKit's GLib API is context-affine --
 * every call must happen on the thread whose main context runs -- so [post]
 * is the only door.
 *
 * The overall shape follows Stremio's shell, which stacks an offscreen web
 * view over an mpv render-API FBO in one scene; WPE is the WebKit engine
 * built for that embedding style, and GLib is its event loop.
 */
object Glib {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.global()
    private lateinit var glib: SymbolLookup

    private fun fn(name: String, desc: FunctionDescriptor) =
        linker.downcallHandle(
            glib.find(name).orElseThrow { UnsatisfiedLinkError("glib: $name") }, desc,
        )

    private val gMainLoopNew by lazy { fn("g_main_loop_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)) }
    private val gMainLoopRun by lazy { fn("g_main_loop_run", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val gMainLoopQuit by lazy { fn("g_main_loop_quit", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val gIdleAdd by lazy { fn("g_idle_add", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
    private val gTimeoutAdd by lazy { fn("g_timeout_add", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)) }

    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private var loop: MemorySegment = MemorySegment.NULL
    @Volatile private var thread: Thread? = null

    // One permanent upcall that drains the queue; G_SOURCE_REMOVE (0) each
    // time, re-armed per post. Keeps the upcall surface to a single stub.
    private val drainStub: MemorySegment by lazy {
        val target = MethodHandles.lookup().findStatic(
            Glib::class.java, "drain",
            MethodType.methodType(Int::class.java, MemorySegment::class.java),
        )
        linker.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS), arena)
    }

    private val ticks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val tickStub: MemorySegment by lazy {
        val target = MethodHandles.lookup().findStatic(
            Glib::class.java, "tick",
            MethodType.methodType(Int::class.java, MemorySegment::class.java),
        )
        linker.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS), arena)
    }

    @JvmStatic
    fun tick(data: MemorySegment): Int {
        for (t in ticks) runCatching { t() }.onFailure { it.printStackTrace() }
        return 1 // G_SOURCE_CONTINUE
    }

    /** Run [task] on the GLib thread every [intervalMs]. */
    fun addTick(intervalMs: Int, task: () -> Unit) {
        ensureStarted()
        val first = ticks.isEmpty()
        ticks.add(task)
        if (first) post { gTimeoutAdd.invokeExact(intervalMs, tickStub, MemorySegment.NULL) as Int; Unit }
    }

    @JvmStatic
    fun drain(data: MemorySegment): Int {
        while (true) {
            val task = queue.poll() ?: break
            runCatching { task() }.onFailure { it.printStackTrace() }
        }
        return 0 // G_SOURCE_REMOVE
    }

    /** Start the loop thread. Idempotent. */
    fun ensureStarted() {
        if (thread != null) return
        synchronized(this) {
            if (thread != null) return
            glib = SymbolLookup.libraryLookup("libglib-2.0.so.0", arena)
            loop = gMainLoopNew.invokeExact(MemorySegment.NULL, 0) as MemorySegment
            thread = Thread({
                gMainLoopRun.invokeExact(loop)
            }, "wpe-glib").apply { isDaemon = true; start() }
        }
    }

    private val delayedQueue = ConcurrentLinkedQueue<() -> Unit>()

    private val delayedStub: MemorySegment by lazy {
        val target = MethodHandles.lookup().findStatic(
            Glib::class.java, "runDelayed",
            MethodType.methodType(Int::class.java, MemorySegment::class.java),
        )
        linker.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS), arena)
    }

    @JvmStatic
    fun runDelayed(data: MemorySegment): Int {
        delayedQueue.poll()?.let { runCatching(it).onFailure { e -> e.printStackTrace() } }
        return 0 // one-shot
    }

    /** Run [task] on the GLib thread after [ms] milliseconds. */
    fun postDelayed(ms: Int, task: () -> Unit) {
        delayedQueue.add(task)
        gTimeoutAdd.invokeExact(ms, delayedStub, MemorySegment.NULL) as Int
    }

    /** Run [task] on the GLib thread. */
    fun post(task: () -> Unit) {
        queue.add(task)
        gIdleAdd.invokeExact(drainStub, MemorySegment.NULL) as Int
    }

    fun quit() {
        if (!loop.equals(MemorySegment.NULL)) gMainLoopQuit.invokeExact(loop)
    }
}
