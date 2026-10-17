package dev.chrisbanes.haze

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/*
 * Haze 1.x's effect API on top of Haze 2.
 *
 * 1.x calls BlurEffect.convertRadiusToSigma, which the Compose build the
 * Wayland host is pinned to removed, so its blur threw on every frame there.
 * 2.0 no longer does, but it replaced the 1.x API. Upstream still writes 1.x
 * (`hazeEffect(state) { blurRadius = ... }`), so this keeps its call sites
 * building unchanged. Delete it when upstream moves to 2.0.
 *
 * Only the properties a call site sets are written; everything else falls
 * through to Haze 2's defaults, which match 1.x's.
 */

class HazeTint(val color: Color, val blendMode: BlendMode = BlendMode.SrcOver)

sealed interface HazeInputScale {
    data object None : HazeInputScale
    data object Auto : HazeInputScale
    data class Fixed(val scale: Float) : HazeInputScale

    companion object {
        val Default: HazeInputScale get() = None
    }
}

class HazeEffectScope internal constructor() {
    private var blurEnabledValue: Boolean? = null
    var blurEnabled: Boolean
        get() = blurEnabledValue ?: true
        set(value) { blurEnabledValue = value }
    var alpha: Float = 1f
    var mask: Brush? = null
    var backgroundColor: Color = Color.Unspecified
    var tints: List<HazeTint> = emptyList()
    var blurRadius: Dp = Dp.Unspecified
    var noiseFactor: Float = -1f
    var fallbackTint: HazeTint? = null
    var inputScale: HazeInputScale = HazeInputScale.Default
    var blurredEdgeTreatment: BlurredEdgeTreatment? = null
    var clipToAreasBounds: Boolean? = null
    var expandLayerBounds: Boolean? = null

    internal fun style(): HazeBlurStyle = HazeBlurStyle {
        blurEnabledValue?.let { blurEnabled(it) }
        if (alpha != 1f) alpha(alpha)
        mask?.let { mask(it) }
        if (backgroundColor.isSpecified) backgroundColor(backgroundColor)
        if (tints.isNotEmpty()) colorEffects(tints.map { it.effect() })
        if (blurRadius.isSpecified) blurRadius(blurRadius)
        if (noiseFactor in 0f..1f) noiseFactor(noiseFactor)
        fallbackTint?.let { fallbackColorEffect(it.effect()) }
        blurredEdgeTreatment?.let { blurredEdgeTreatment(it) }
    }

    internal fun performanceMode(): HazePerformanceMode? = when (val scale = inputScale) {
        HazeInputScale.None -> HazePerformanceMode.Quality
        HazeInputScale.Auto -> null
        // Haze 2 scales by sqrt(0.25 + 0.75 * quality); invert it.
        is HazeInputScale.Fixed ->
            HazePerformanceMode.Fixed(((scale.scale * scale.scale - 0.25f) / 0.75f).coerceIn(0f, 1f))
    }

    private fun HazeTint.effect(): HazeColorEffect = HazeColorEffect.tint(color, blendMode)
}

/** 1.x's `hazeEffect(state) { ... }`: blurs the sources of [state] behind this node. */
fun Modifier.hazeEffect(
    state: HazeState?,
    block: (HazeEffectScope.() -> Unit)? = null,
): Modifier {
    val scope = HazeEffectScope().apply { block?.invoke(this) }
    return hazeBlur(
        input = state?.let { HazeInput.Sources(it) } ?: HazeInput.Content,
        style = scope.style(),
        performanceMode = scope.performanceMode(),
        expandLayerBounds = scope.expandLayerBounds ?: true,
    )
}
