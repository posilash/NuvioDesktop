// Proof-of-concept shim for running Skiko on Wayland/EGL.
//
// Skia's GrGLMakeNativeInterface() on Linux is GLX-based:
//
//     if (nullptr == glXGetCurrentContext()) { return nullptr; }
//     return GrGLMakeAssembledInterface(nullptr, glx_get);
//
// Under Wayland the context is EGL, so glXGetCurrentContext() is NULL and Skia
// refuses to build an interface -- which is the "Can't create OpenGL
// DirectContext" failure. The GL entry points themselves are fine: with
// libglvnd, glXGetProcAddress returns dispatch stubs that route to whatever
// context is current, EGL included.
//
// So only the guard is wrong. Return a non-NULL sentinel and let the assembly
// proceed. This is a proof of concept, not a fix; the real fix is a Skiko/Skia
// build with EGL support.
void *glXGetCurrentContext(void) { return (void *) 1; }
