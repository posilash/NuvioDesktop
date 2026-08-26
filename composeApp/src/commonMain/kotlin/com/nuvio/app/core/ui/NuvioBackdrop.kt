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
    internal var layer: GraphicsLayer? = null
    internal var sourceOrigin by mutableStateOf(Offset.Zero)
    internal var effectOrigin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberNuvioBackdropState(): NuvioBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { NuvioBackdropState().apply { this.layer = layer } }
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
            if (layer == null) {
                drawRect(tint)
                return@drawBehind
            }
            val radiusPx = blurRadius.toPx()
            layer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
            // The recording is in the source's coordinates; shift it so the
            // part behind this panel lands under this panel.
            val dx = state.sourceOrigin.x - state.effectOrigin.x
            val dy = state.sourceOrigin.y - state.effectOrigin.y
            clipRect {
                translate(dx, dy) { drawLayer(layer) }
            }
            drawRect(tint)
        }
