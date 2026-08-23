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

## Verified

    GLFW platform: Wayland
    GL_RENDERER: NVIDIA GeForce RTX 3070 Laptop GPU/PCIe/SSE2
    RESULT: rendered 120 frames on Wayland
    OK: clean shutdown

GLFW is asked for `GLFW_PLATFORM_WAYLAND` explicitly, so falling back to
XWayland would be a visible failure rather than a silent one.
