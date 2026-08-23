package com.nuvio.wayland

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Minimal libmpv render-API binding over the Foreign Function & Memory API.
 *
 * Deliberately not JNI: this needs no native build step, and the render API is
 * a small enough surface that binding it directly is less code than a wrapper
 * would be.
 *
 * Only what the Wayland host needs is bound. The interesting part is
 * [MPV_RENDER_API_TYPE_OPENGL_NEXT]: it takes exactly the same parameters as
 * the long-standing "opengl" type but renders with libplacebo -- the same
 * renderer vo=gpu-next uses -- rather than the legacy gl_video one. For a
 * client that is a one-string change.
 */
class Mpv private constructor(private val handle: MemorySegment, private val arena: Arena) {

    companion object {
        const val MPV_RENDER_PARAM_INVALID = 0
        const val MPV_RENDER_PARAM_API_TYPE = 1
        const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
        const val MPV_RENDER_PARAM_OPENGL_FBO = 3
        const val MPV_RENDER_PARAM_FLIP_Y = 4
        const val MPV_RENDER_PARAM_ADVANCED_CONTROL = 10

        const val MPV_RENDER_UPDATE_FRAME = 1L

        const val MPV_RENDER_API_TYPE_OPENGL = "opengl"
        const val MPV_RENDER_API_TYPE_OPENGL_NEXT = "opengl-next"

        // struct mpv_render_param { int type; void *data; }  -- 4 + 4 pad + 8
        private val RENDER_PARAM: MemoryLayout = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("data"),
        )

        // struct mpv_opengl_fbo { int fbo, w, h, internal_format; }
        private val OPENGL_FBO: MemoryLayout = MemoryLayout.structLayout(
            JAVA_INT.withName("fbo"),
            JAVA_INT.withName("w"),
            JAVA_INT.withName("h"),
            JAVA_INT.withName("internal_format"),
        )

        private val linker: Linker = Linker.nativeLinker()
        private lateinit var lookup: SymbolLookup

        private fun fn(name: String, desc: FunctionDescriptor) =
            linker.downcallHandle(
                lookup.find(name).orElseThrow { UnsatisfiedLinkError("libmpv: $name") },
                desc,
            )

        private val mpvCreate by lazy { fn("mpv_create", FunctionDescriptor.of(ADDRESS)) }
        private val mpvInitialize by lazy {
            fn("mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        }
        private val mpvSetOptionString by lazy {
            fn("mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        }
        private val mpvCommand by lazy {
            fn("mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }
        private val mpvTerminateDestroy by lazy {
            fn("mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS))
        }
        private val mpvRenderContextCreate by lazy {
            fn("mpv_render_context_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        }
        private val mpvRenderContextRender by lazy {
            fn("mpv_render_context_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }
        private val mpvRenderContextUpdate by lazy {
            fn("mpv_render_context_update", FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_LONG, ADDRESS))
        }
        private val mpvRenderContextFree by lazy {
            fn("mpv_render_context_free", FunctionDescriptor.ofVoid(ADDRESS))
        }

        /** Load libmpv. Pass an explicit path to use a build other than the system one. */
        fun load(path: String?): Boolean = runCatching {
            val arena = Arena.global()
            lookup = if (path != null) {
                SymbolLookup.libraryLookup(java.nio.file.Path.of(path), arena)
            } else {
                SymbolLookup.libraryLookup("mpv", arena)
            }
            true
        }.getOrElse { false }

        // glibc LC_NUMERIC. mpv_create() returns NULL unless LC_NUMERIC is "C",
        // and the JVM sets a locale from the environment, so this has to be
        // forced first. (Nuvio's C++ bridge does the same via <clocale>.)
        private const val LC_NUMERIC = 1

        private fun forceCNumericLocale() {
            runCatching {
                val libc = Linker.nativeLinker().defaultLookup()
                val setlocale = linker.downcallHandle(
                    libc.find("setlocale").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS),
                )
                Arena.ofConfined().use { a ->
                    setlocale.invokeExact(LC_NUMERIC, a.allocateFrom("C")) as MemorySegment
                }
            }
        }

        fun create(): Mpv {
            forceCNumericLocale()
            val arena = Arena.ofShared()
            val h = mpvCreate.invokeExact() as MemorySegment
            check(!h.equals(MemorySegment.NULL)) {
                "mpv_create failed (LC_NUMERIC must be \"C\")"
            }
            return Mpv(h, arena)
        }
    }

    private var renderCtx: MemorySegment = MemorySegment.NULL
    private var procAddressStub: MemorySegment = MemorySegment.NULL

    fun setOption(name: String, value: String) {
        val r = mpvSetOptionString.invokeExact(
            handle, arena.allocateFrom(name), arena.allocateFrom(value),
        ) as Int
        check(r >= 0) { "mpv_set_option_string($name) -> $r" }
    }

    fun initialize() {
        val r = mpvInitialize.invokeExact(handle) as Int
        check(r >= 0) { "mpv_initialize -> $r" }
    }

    fun command(vararg args: String) {
        val arr = arena.allocate(ADDRESS, (args.size + 1).toLong())
        args.forEachIndexed { i, a -> arr.setAtIndex(ADDRESS, i.toLong(), arena.allocateFrom(a)) }
        arr.setAtIndex(ADDRESS, args.size.toLong(), MemorySegment.NULL)
        mpvCommand.invokeExact(handle, arr) as Int
    }

    /**
     * Create the render context. [getProcAddress] resolves GL entry points; the
     * host supplies it because mpv must use the same loader the window system
     * does -- here GLFW's, so the EGL context GLFW created is the one mpv sees.
     */
    fun createRenderContext(apiType: String, getProcAddress: (String) -> Long) {
        val upcallType = MethodType.methodType(
            MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
        )
        val target = MethodHandles.lookup().findStatic(
            ProcAddressBridge::class.java, "resolve", upcallType,
        )
        ProcAddressBridge.resolver = getProcAddress
        procAddressStub = linker.upcallStub(
            target,
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            arena,
        )

        // struct mpv_opengl_init_params { get_proc_address; ctx; }
        val glInit = arena.allocate(ADDRESS, 2)
        glInit.setAtIndex(ADDRESS, 0, procAddressStub)
        glInit.setAtIndex(ADDRESS, 1, MemorySegment.NULL)

        val advanced = arena.allocateFrom(JAVA_INT, 1)

        val params = arena.allocate(RENDER_PARAM, 4)
        fun put(i: Int, type: Int, data: MemorySegment) {
            val off = i.toLong() * RENDER_PARAM.byteSize()
            params.set(JAVA_INT, off, type)
            params.set(ADDRESS, off + 8, data)
        }
        put(0, MPV_RENDER_PARAM_API_TYPE, arena.allocateFrom(apiType))
        put(1, MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, glInit)
        put(2, MPV_RENDER_PARAM_ADVANCED_CONTROL, advanced)
        put(3, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)

        val out = arena.allocate(ADDRESS)
        val r = mpvRenderContextCreate.invokeExact(out, handle, params) as Int
        check(r >= 0) { "mpv_render_context_create($apiType) -> $r" }
        renderCtx = out.get(ADDRESS, 0)
    }

    fun hasNewFrame(): Boolean {
        if (renderCtx.equals(MemorySegment.NULL)) return false
        val flags = mpvRenderContextUpdate.invokeExact(renderCtx) as Long
        return (flags and MPV_RENDER_UPDATE_FRAME) != 0L
    }

    /** Render one frame into [fbo] at [w] x [h]. */
    fun render(fbo: Int, w: Int, h: Int) {
        if (renderCtx.equals(MemorySegment.NULL)) return
        Arena.ofConfined().use { frame ->
            val target = frame.allocate(OPENGL_FBO)
            target.set(JAVA_INT, 0, fbo)
            target.set(JAVA_INT, 4, w)
            target.set(JAVA_INT, 8, h)
            target.set(JAVA_INT, 12, 0) // internal_format: 0 = let mpv choose
            val flip = frame.allocateFrom(JAVA_INT, 0)

            val params = frame.allocate(RENDER_PARAM, 3)
            fun put(i: Int, type: Int, data: MemorySegment) {
                val off = i.toLong() * RENDER_PARAM.byteSize()
                params.set(JAVA_INT, off, type)
                params.set(ADDRESS, off + 8, data)
            }
            put(0, MPV_RENDER_PARAM_OPENGL_FBO, target)
            put(1, MPV_RENDER_PARAM_FLIP_Y, flip)
            put(2, MPV_RENDER_PARAM_INVALID, MemorySegment.NULL)

            mpvRenderContextRender.invokeExact(renderCtx, params) as Int
        }
    }

    fun close() {
        if (!renderCtx.equals(MemorySegment.NULL)) {
            mpvRenderContextFree.invokeExact(renderCtx)
            renderCtx = MemorySegment.NULL
        }
        mpvTerminateDestroy.invokeExact(handle)
        arena.close()
    }
}

/** Upcall target for mpv's GL loader. FFM upcalls need a static method. */
internal object ProcAddressBridge {
    @JvmStatic
    var resolver: ((String) -> Long)? = null

    @JvmStatic
    fun resolve(ctx: MemorySegment, name: MemorySegment): MemorySegment {
        val n = name.reinterpret(Long.MAX_VALUE).getString(0)
        val addr = resolver?.invoke(n) ?: 0L
        return MemorySegment.ofAddress(addr)
    }
}
