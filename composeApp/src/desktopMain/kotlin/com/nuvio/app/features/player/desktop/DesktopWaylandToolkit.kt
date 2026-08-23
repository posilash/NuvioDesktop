package com.nuvio.app.features.player.desktop

/**
 * Whether AWT is running on its Wayland toolkit rather than X11.
 *
 * Standard OpenJDK has no Wayland backend on Linux; it arrives with Project
 * Wakefield as `WLToolkit`, selected with `-Dawt.toolkit.name=WLToolkit` on a
 * JDK that ships `libawt_wlawt.so`. On any other JDK the property is ignored
 * and AWT stays on X11, so this reports false and nothing changes.
 *
 * Several parts of the desktop player are X11-only by construction and have to
 * behave differently when this is true:
 *
 *  - `initGtkEarly()` pins GDK to the X11 backend for the WebKitGTK control
 *    overlay, which is captured with XComposite.
 *  - The native player embeds mpv into a heavyweight AWT Canvas via mpv's
 *    "wid", which needs an X11 window id. Wayland has no equivalent, so the
 *    embed has to be replaced by the libmpv render API.
 */
internal object DesktopWaylandToolkit {
    val inUse: Boolean by lazy {
        System.getProperty("awt.toolkit.name")?.equals("WLToolkit", ignoreCase = true) == true
    }
}
