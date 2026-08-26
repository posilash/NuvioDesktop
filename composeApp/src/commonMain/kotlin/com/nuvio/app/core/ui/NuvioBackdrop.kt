package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp

/**
 * Backdrop blur, on Compose's own primitives.
 *
 * haze does this far better, but it calls BlurEffect.convertRadiusToSigma,
 * which 1.12 deleted, so every frame throws against that version. The pieces it
 * needs survived: a GraphicsLayer records the content behind, a BlurEffect goes
 * on the layer, and the panel draws it offset and clipped to its own bounds.
 *
 * Deliberately small. It handles one source and one effect, does not follow
 * scroll or transform beyond position, and exists so the panels that were
 * frosted glass do not become flat rectangles. Delete it when haze builds
 * against 1.12.
 */
class NuvioBackdropState {
    /** Holds the recorded content, and is never given a render effect. */
    internal var layer: GraphicsLayer? = null

    /**
     * Carries the blur. A separate layer because a GraphicsLayer has one
     * renderEffect and two things draw this content: the source draws it
     * sharp, the panel draws it blurred. Sharing one layer meant the effect
     * the panel set was still on it when the source drew the next frame, so
     * the whole backdrop came up blurred -- intermittently, depending on
     * whether a panel had appeared yet.
     */
    internal var blurLayer: GraphicsLayer? = null
    internal var sourceOrigin by mutableStateOf(Offset.Zero)
    internal var effectOrigin by mutableStateOf(Offset.Zero)
    /**
     * Radius the layer's effect was built for. A RenderEffect is a native Skia
     * image filter, and building one per frame leaks it: the peer is freed from
     * a cleaner, and this draws far faster than the heap grows enough to
     * collect. Measured at ~50MB/s under the Vulkan host.
     */
    internal var appliedRadius = Float.NaN
}

@Composable
fun rememberNuvioBackdropState(): NuvioBackdropState {
    val layer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    return remember(layer, blurLayer) {
        NuvioBackdropState().apply {
            this.layer = layer
            this.blurLayer = blurLayer
        }
    }
}

/** The content to be blurred. Draws normally; also records itself. */
fun Modifier.nuvioBackdropSource(state: NuvioBackdropState): Modifier =
    this.onGloballyPositioned { state.sourceOrigin = it.positionInRoot() }
        .drawWithContent {
            val layer = state.layer
            if (layer == null) {
                drawContent()
                return@drawWithContent
            }
            // Record and draw the same content: the panel reads the recording
            // later in the frame, by which point it holds this frame's pixels.
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }

/** Draws the recorded backdrop, blurred and tinted, behind this content. */
fun Modifier.nuvioBackdropEffect(
    state: NuvioBackdropState,
    blurRadius: Dp,
    tint: Color,
): Modifier =
    this.onGloballyPositioned { state.effectOrigin = it.positionInRoot() }
        .drawBehind {
            val layer = state.layer
            val blurLayer = state.blurLayer
            if (layer == null || blurLayer == null) {
                drawRect(tint)
                return@drawBehind
            }
            val radiusPx = blurRadius.toPx()
            if (state.appliedRadius != radiusPx) {
                state.appliedRadius = radiusPx
                blurLayer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
            }
            // The blurred copy: one cheap layer-to-layer draw, so the source's
            // own recording stays sharp for its own drawing.
            if (layer.size.width > 0 && layer.size.height > 0) {
                blurLayer.record(size = layer.size) { drawLayer(layer) }
            }
            // The recording is in the source's coordinates; shift it so the
            // part behind this panel lands under this panel.
            val dx = state.sourceOrigin.x - state.effectOrigin.x
            val dy = state.sourceOrigin.y - state.effectOrigin.y
            clipRect {
                translate(dx, dy) { drawLayer(blurLayer) }
            }
            drawRect(tint)
        }
