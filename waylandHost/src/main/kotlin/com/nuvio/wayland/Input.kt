@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.nuvio.wayland

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.InternalKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import org.lwjgl.glfw.GLFW.*
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Routes GLFW input into a [ComposeScene].
 *
 * Nothing here touches AWT as a windowing system; `java.awt.event.KeyEvent` is
 * referenced only for its virtual-key constants, which are what Compose's
 * desktop [Key] values are built from. GLFW's key codes are its own, so they
 * have to be translated.
 */
@OptIn(ExperimentalComposeUiApi::class)
class InputRouter(
    private val window: Long,
    private val scene: ComposeScene,
) {
    private var cursorX = 0f
    private var cursorY = 0f
    private var buttonMask = 0
    private var modifiers = PointerKeyboardModifiers(0)

    /** Scale from window coordinates to framebuffer pixels (fractional scaling). */
    var scale: Float = 1f

    /**
     * Optional web-chrome sink. When set, input is ALSO forwarded to the WPE
     * view -- the stock model: the chrome overlay sees everything over the
     * player and its page decides what reacts. Coordinates are mapped from
     * framebuffer pixels into the chrome's own surface size.
     */
    var chrome: com.nuvio.wayland.wpe.WpeChrome? = null

    /**
     * Called when input the page may answer with a toast reaches it. The page
     * only reports activity while its controls are up, so a volume toast
     * raised over hidden controls asks for no frames and nothing composites it.
     */
    var onChromeInput: (() -> Unit)? = null

    /**
     * Thumb button pressed, as the chrome event name the page would have sent.
     * Upstream's Linux bridge synthesises the same two from GTK so both
     * platforms end up in one Kotlin handler; the host is that bridge here.
     */
    var onThumbButton: ((String) -> Unit)? = null
    var chromeScaleX: Float = 1f
    var chromeScaleY: Float = 1f
    private var chromeButtons = 0

    private fun chromeXY(): Pair<Int, Int> =
        Pair((cursorX * chromeScaleX).toInt(), (cursorY * chromeScaleY).toInt())

    // Counted at both ends: what GLFW handed us, and what actually reached the
    // scene. If those diverge the events are being dropped between the two,
    // which is a different fault from never receiving any.
    private var received = 0L
    private var delivered = 0L
    private var keys = 0L
    private var lastReport = 0L

    /** Per-second summary of input, or null until a second has passed. */
    fun report(now: Long): String? {
        if (lastReport == 0L) {
            lastReport = now
            return null
        }
        if (now - lastReport < 1_000_000_000L) return null
        lastReport = now
        val r = received; val d = delivered; val k = keys
        received = 0; delivered = 0; keys = 0
        return "input: glfwEvents/s=$r deliveredToScene/s=$d keys/s=$k " +
            "cursor=${cursorX.toInt()},${cursorY.toInt()} scale=$scale"
    }

    fun install() {
        glfwSetCursorPosCallback(window) { _, x, y ->
            cursorX = (x * scale).toFloat()
            cursorY = (y * scale).toFloat()
            chrome?.let { c ->
                val (cx, cy) = chromeXY()
                c.dispatchPointer(motion = true, x = cx, y = cy, button = 0,
                    pressed = false, modifiers = chromeButtons)
            }
            send(PointerEventType.Move)
        }

        glfwSetMouseButtonCallback(window) { _, button, action, mods ->
            modifiers = mods.toComposeModifiers()
            val composeButton = when (button) {
                GLFW_MOUSE_BUTTON_LEFT -> PointerButton.Primary
                GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
                GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
                // Dropping these is why the thumb buttons did nothing here.
                // X11 numbers them 8 and 9 and upstream has to re-post them for
                // AWT, which reads them as 6 and 7; GLFW hands them over
                // already separated, so they only need naming.
                GLFW_MOUSE_BUTTON_4 -> PointerButton.Back
                GLFW_MOUSE_BUTTON_5 -> PointerButton.Forward
                else -> return@glfwSetMouseButtonCallback
            }
            if (action == GLFW_PRESS && composeButton != PointerButton.Primary) {
                when (button) {
                    GLFW_MOUSE_BUTTON_4 -> onThumbButton?.invoke("seekBack")
                    GLFW_MOUSE_BUTTON_5 -> onThumbButton?.invoke("seekForward")
                }
            }
            // Compose expects the button mask to already reflect the new state
            // when the press arrives, and to have it cleared before the release.
            val mask = 1 shl button
            buttonMask = if (action == GLFW_PRESS) {
                buttonMask or mask
            } else {
                buttonMask and mask.inv()
            }
            chrome?.let { c ->
                // wpe buttons: 1=left 2=middle 3=right; button-held modifier
                // bits start at 1<<20 (wpe_input_pointer_modifier_button1).
                val wpeButton = when (button) {
                    GLFW_MOUSE_BUTTON_LEFT -> 1
                    GLFW_MOUSE_BUTTON_MIDDLE -> 2
                    GLFW_MOUSE_BUTTON_RIGHT -> 3
                    else -> 0
                }
                if (wpeButton != 0) {
                    val bit = 1 shl (19 + wpeButton)
                    chromeButtons = if (action == GLFW_PRESS) chromeButtons or bit
                    else chromeButtons and bit.inv()
                    val (cx, cy) = chromeXY()
                    c.dispatchPointer(motion = false, x = cx, y = cy,
                        button = wpeButton, pressed = action == GLFW_PRESS,
                        modifiers = chromeButtons)
                }
            }
            send(
                if (action == GLFW_PRESS) PointerEventType.Press else PointerEventType.Release,
                button = composeButton,
            )
        }

        glfwSetScrollCallback(window) { _, dx, dy ->
            chrome?.let { c ->
                val (cx, cy) = chromeXY()
                // Web wheel steps: ~53px per notch, sign as Wayland's.
                if (dy != 0.0) c.dispatchAxis(cx, cy, vertical = true, value = (dy * 53).toInt())
                if (dx != 0.0) c.dispatchAxis(cx, cy, vertical = false, value = (dx * 53).toInt())
                // The wheel is the page's other volume control.
                onChromeInput?.invoke()
            }
            // Compose scroll deltas run opposite to GLFW's, and are in
            // "lines"; one notch is one unit.
            send(PointerEventType.Scroll, scroll = Offset(-dx.toFloat(), -dy.toFloat()))
        }

        glfwSetCursorEnterCallback(window) { _, entered ->
            send(if (entered) PointerEventType.Enter else PointerEventType.Exit)
        }

        glfwSetKeyCallback(window) { _, key, scancode, action, mods ->
            modifiers = mods.toComposeModifiers()
            val type = when (action) {
                GLFW_PRESS, GLFW_REPEAT -> KeyEventType.KeyDown
                GLFW_RELEASE -> KeyEventType.KeyUp
                else -> return@glfwSetKeyCallback
            }
            chrome?.let { c ->
                val keysym = key.toXkbKeysym(mods)
                if (keysym != 0) {
                    var wm = 0
                    if (mods and GLFW_MOD_CONTROL != 0) wm = wm or 1
                    if (mods and GLFW_MOD_SHIFT != 0) wm = wm or 2
                    if (mods and GLFW_MOD_ALT != 0) wm = wm or 4
                    if (mods and GLFW_MOD_SUPER != 0) wm = wm or 8
                    // hardware_key_code is what WebKit turns into DOM
                    // event.code ("Space", "ArrowLeft"...), and it must be an
                    // XKB keycode = evdev scancode + 8. Passing the GLFW key
                    // enum here left event.code garbage, which silently
                    // killed every code-based shortcut in controls.js.
                    c.dispatchKey(keysym, scancode + 8, action != GLFW_RELEASE, wm)
                    if (action != GLFW_RELEASE) onChromeInput?.invoke()
                }
            }
            val vk = key.toAwtVirtualKey() ?: return@glfwSetKeyCallback
            val mods = modifiers
            received++
            keys++
            onUiThread {
                delivered++
                val consumed = scene.sendKeyEvent(
                    KeyEvent(InternalKeyEvent(Key(vk), type, 0, mods, null)),
                )
                // Back, but only for a backspace nothing else wanted: a text
                // field editing its own content consumes it, so typing in the
                // search box never navigates.
                if (!consumed && type == KeyEventType.KeyDown && vk == AwtKeyEvent.VK_BACK_SPACE) {
                    com.nuvio.app.core.ui.DesktopBackDispatcher.back()
                }
            }
        }

        // Text input arrives separately from key events: GLFW's key callback
        // reports physical keys, while this reports the composed character,
        // which is what text fields need. The codepoint alone is not enough:
        // Compose's desktop isTypedEvent demands an AWT KEY_TYPED (id 400)
        // nativeEvent -- decompiled from TextFieldKeyInput.desktop.kt -- and
        // silently inserts nothing without one. So the character is dressed up
        // as exactly the event AWT would have delivered.
        glfwSetCharCallback(window) { _, codepoint ->
            val mods = modifiers
            onUiThread {
                for (ch in Character.toChars(codepoint)) {
                    val awtTyped = java.awt.event.KeyEvent(
                        awtEventSource,
                        java.awt.event.KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(),
                        0,
                        java.awt.event.KeyEvent.VK_UNDEFINED,
                        ch,
                    )
                    scene.sendKeyEvent(
                        KeyEvent(
                            InternalKeyEvent(
                                Key.Unknown, KeyEventType.KeyDown, ch.code, mods, awtTyped,
                            ),
                        ),
                    )
                }
            }
        }
    }

    // AWT events want a Component source; this one never has a peer and never
    // shows -- it exists only to satisfy the constructor.
    private val awtEventSource = java.awt.Container()

    /**
     * How to reach the thread that owns the scene. Compose state must only be
     * touched from the thread that renders it, and which thread that is depends
     * on the host's configuration: the EDT on the legacy in-loop path, the
     * dedicated UI thread when [UiPipeline] is driving. Main sets this.
     */
    var dispatch: ((() -> Unit) -> Unit) = { block -> java.awt.EventQueue.invokeLater(block) }

    private fun onUiThread(block: () -> Unit) = dispatch(block)

    private fun send(
        type: PointerEventType,
        scroll: Offset = Offset.Zero,
        button: PointerButton? = null,
    ) = run {
        received++
        onUiThread {
        delivered++
        scene.sendPointerEvent(
            eventType = type,
            position = Offset(cursorX, cursorY),
            scrollDelta = scroll,
            timeMillis = System.currentTimeMillis(),
            type = PointerType.Mouse,
            buttons = PointerButtons(buttonMask),
            keyboardModifiers = modifiers,
            button = button,
        )
        }
    }

    private fun Int.toComposeModifiers(): PointerKeyboardModifiers {
        // Bit layout matches java.awt.event.InputEvent's extended modifiers,
        // which is what Compose's desktop implementation reads.
        var m = 0
        if (this and GLFW_MOD_SHIFT != 0) m = m or (1 shl 6)
        if (this and GLFW_MOD_CONTROL != 0) m = m or (1 shl 7)
        if (this and GLFW_MOD_ALT != 0) m = m or (1 shl 9)
        if (this and GLFW_MOD_SUPER != 0) m = m or (1 shl 8)
        return PointerKeyboardModifiers(m)
    }

    /**
     * GLFW key code to AWT virtual key. Compose's desktop [Key] values are
     * defined in terms of AWT constants, so this bridge is unavoidable.
     * Printable ASCII maps directly; the rest is a table.
     */
    /**
     * GLFW key to XKB keysym, which is what wpe_input_keyboard_event carries.
     * Basic-latin keysyms equal their ASCII codes (lowercased); the rest come
     * from keysymdef.h.
     */
    private fun Int.toXkbKeysym(mods: Int): Int = when (this) {
        in GLFW_KEY_A..GLFW_KEY_Z ->
            if (mods and GLFW_MOD_SHIFT != 0) this else this + 32 // 'A'->'a'
        in GLFW_KEY_0..GLFW_KEY_9 -> this
        GLFW_KEY_SPACE -> 0x20
        GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> 0xFF0D
        GLFW_KEY_ESCAPE -> 0xFF1B
        GLFW_KEY_TAB -> 0xFF09
        GLFW_KEY_BACKSPACE -> 0xFF08
        GLFW_KEY_DELETE -> 0xFFFF
        GLFW_KEY_LEFT -> 0xFF51
        GLFW_KEY_UP -> 0xFF52
        GLFW_KEY_RIGHT -> 0xFF53
        GLFW_KEY_DOWN -> 0xFF54
        GLFW_KEY_HOME -> 0xFF50
        GLFW_KEY_END -> 0xFF57
        GLFW_KEY_PAGE_UP -> 0xFF55
        GLFW_KEY_PAGE_DOWN -> 0xFF56
        in GLFW_KEY_F1..GLFW_KEY_F12 -> 0xFFBE + (this - GLFW_KEY_F1)
        else -> 0
    }

    private fun Int.toAwtVirtualKey(): Int? = when (this) {
        in GLFW_KEY_A..GLFW_KEY_Z -> AwtKeyEvent.VK_A + (this - GLFW_KEY_A)
        in GLFW_KEY_0..GLFW_KEY_9 -> AwtKeyEvent.VK_0 + (this - GLFW_KEY_0)
        in GLFW_KEY_F1..GLFW_KEY_F12 -> AwtKeyEvent.VK_F1 + (this - GLFW_KEY_F1)
        GLFW_KEY_SPACE -> AwtKeyEvent.VK_SPACE
        GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> AwtKeyEvent.VK_ENTER
        GLFW_KEY_ESCAPE -> AwtKeyEvent.VK_ESCAPE
        GLFW_KEY_TAB -> AwtKeyEvent.VK_TAB
        GLFW_KEY_BACKSPACE -> AwtKeyEvent.VK_BACK_SPACE
        GLFW_KEY_DELETE -> AwtKeyEvent.VK_DELETE
        GLFW_KEY_INSERT -> AwtKeyEvent.VK_INSERT
        GLFW_KEY_HOME -> AwtKeyEvent.VK_HOME
        GLFW_KEY_END -> AwtKeyEvent.VK_END
        GLFW_KEY_PAGE_UP -> AwtKeyEvent.VK_PAGE_UP
        GLFW_KEY_PAGE_DOWN -> AwtKeyEvent.VK_PAGE_DOWN
        GLFW_KEY_LEFT -> AwtKeyEvent.VK_LEFT
        GLFW_KEY_RIGHT -> AwtKeyEvent.VK_RIGHT
        GLFW_KEY_UP -> AwtKeyEvent.VK_UP
        GLFW_KEY_DOWN -> AwtKeyEvent.VK_DOWN
        GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> AwtKeyEvent.VK_SHIFT
        GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> AwtKeyEvent.VK_CONTROL
        GLFW_KEY_LEFT_ALT, GLFW_KEY_RIGHT_ALT -> AwtKeyEvent.VK_ALT
        GLFW_KEY_LEFT_SUPER, GLFW_KEY_RIGHT_SUPER -> AwtKeyEvent.VK_META
        GLFW_KEY_MINUS -> AwtKeyEvent.VK_MINUS
        GLFW_KEY_EQUAL -> AwtKeyEvent.VK_EQUALS
        GLFW_KEY_COMMA -> AwtKeyEvent.VK_COMMA
        GLFW_KEY_PERIOD -> AwtKeyEvent.VK_PERIOD
        GLFW_KEY_SLASH -> AwtKeyEvent.VK_SLASH
        GLFW_KEY_SEMICOLON -> AwtKeyEvent.VK_SEMICOLON
        GLFW_KEY_APOSTROPHE -> AwtKeyEvent.VK_QUOTE
        GLFW_KEY_LEFT_BRACKET -> AwtKeyEvent.VK_OPEN_BRACKET
        GLFW_KEY_RIGHT_BRACKET -> AwtKeyEvent.VK_CLOSE_BRACKET
        GLFW_KEY_BACKSLASH -> AwtKeyEvent.VK_BACK_SLASH
        GLFW_KEY_GRAVE_ACCENT -> AwtKeyEvent.VK_BACK_QUOTE
        else -> null
    }
}
