# Wayland host (AWT-free Compose)

Runs Compose natively on Wayland, without AWT.

## Why this exists

Compose Desktop renders into an AWT Canvas and Skiko takes that canvas's native
surface through JAWT. **No JDK implements JAWT for AWT's Wayland toolkit** —
not upstream Wakefield, and not JetBrains Runtime, whose `libawt_wlawt.so`
exports zero JAWT symbols against `libawt_xawt.so`'s six. So Compose cannot
start under `-Dawt.toolkit.name=WLToolkit`; it dies in `SkiaLayer.addNotify()`
with `Can't lock DrawingSurface`, and the app silently runs on XWayland instead.

IntelliJ runs on Wayland because Swing draws through Java2D, which never calls
JAWT. That does not carry over to Compose.

Skiko's X11/GLX dependency, however, is in its *AWT windowing layer*, not in
its Skia binding: `DirectContext.makeGL()` binds to whatever GL context is
already current. So driving `ComposeScene` ourselves, against a window and
context we create, avoids AWT entirely. This is the shape of JetBrains' own
AWT-free sample (`experimental/lwjgl-integration`).

## Run

    ./gradlew :waylandHost:run

## The one remaining hack

Skia's `GrGLMakeNativeInterface()` on Linux is GLX-based:

    if (nullptr == glXGetCurrentContext()) { return nullptr; }
    return GrGLMakeAssembledInterface(nullptr, glx_get);

Under Wayland the current context is EGL, so that guard trips and
`DirectContext.makeGL()` fails with `Can't create OpenGL DirectContext`. The GL
entry points are fine — with libglvnd, `glXGetProcAddress` returns dispatch
stubs that route to whichever context is current. Only the guard is wrong.

`native/glxshim.c` returns a non-NULL sentinel from `glXGetCurrentContext` so
the interface gets assembled. Build and preload it:

    gcc -shared -fPIC -o /tmp/libglxshim.so native/glxshim.c
    LD_PRELOAD=/tmp/libglxshim.so ./gradlew :waylandHost:run

This is a proof of concept, not a shipping answer. The real fix is a Skiko/Skia
build with EGL support, which would make the shim unnecessary.

## Video

Pass a file to render mpv underneath the Compose UI:

    gcc -shared -fPIC -o /tmp/libglxshim.so native/glxshim.c
    LD_PRELOAD=/tmp/libglxshim.so ./gradlew :waylandHost:run \
        -Pnuvio.wayland.media=/path/to/file.mkv \
        -Pnuvio.wayland.libmpv=/path/to/libmpv.so.2 \
        -Pnuvio.wayland.hwdec=nvdec

mpv renders into the same GL context, beneath Compose, through the libmpv
render API with `api-type=opengl-next` -- the libplacebo renderer, so this keeps
vo=gpu-next quality instead of the legacy gl_video one. There is no embedded
window, no "wid", and no XComposite capture of a control overlay: the UI is just
Compose drawing on top of the video in one framebuffer.

libmpv is bound with FFM (java.lang.foreign) rather than JNI, so it needs no
native build. Two things that bite:

  - mpv_create() returns NULL unless LC_NUMERIC is "C"; the JVM sets a locale
    from the environment, so setlocale() has to be called first.
  - Skia caches GL state, and mpv issues its own GL calls against the same
    context, so DirectContext.resetGLAll() is required between the two.

## The real app

    LD_PRELOAD=/tmp/libglxshim.so ./gradlew :waylandHost:run -Pnuvio.wayland.realApp=true

renders `com.nuvio.app.App()` -- the same root composable `Main.kt` shows in its
AWT window -- with no change to composeApp.

### Threading

Compose's lifecycle enforces that state changes happen on "the main thread",
which on desktop means the AWT event queue; GLFW requires the real main thread
for event polling. Both are satisfied by splitting them: the EDT owns the GL
context and does all rendering and all Compose work, and the main thread only
polls GLFW and forwards input to the EDT. Everything touching GL -- context
creation, the Skia surface, mpv's render context -- has to run there too, or
LWJGL aborts the VM with "No context is current".

This is AWT the event loop, not AWT the window system. There is no Canvas and
no JAWT, so nothing drags the process back onto X11.

### Input

`Input.kt` routes GLFW callbacks into the scene: pointer move/press/release,
scroll, enter/exit, keys and text. Two wrinkles worth knowing:

  - Compose's desktop `Key` values are defined in terms of AWT virtual-key
    constants, so GLFW key codes need translating. Only the constants are used;
    no AWT event is constructed.
  - Pointer positions arrive in window coordinates but the scene works in
    framebuffer pixels, which differ under fractional scaling.

## Verified

    GLFW platform: Wayland
    GL_RENDERER: NVIDIA GeForce RTX 3070 Laptop GPU/PCIe/SSE2
    RESULT: rendered 120 frames on Wayland
    OK: clean shutdown

With video, on Wayland:

    mpv render context: opengl-next
    Using hardware decoding (nvdec).
    VO: [libmpv] 1280x720 cuda[nv12]        # zero-copy
    VO: [libmpv] 1920x1080 yuv420p10        # HDR10 10-bit
    RESULT: rendered 120 frames on Wayland

GLFW is asked for `GLFW_PLATFORM_WAYLAND` explicitly, so falling back to
XWayland would be a visible failure rather than a silent one.
