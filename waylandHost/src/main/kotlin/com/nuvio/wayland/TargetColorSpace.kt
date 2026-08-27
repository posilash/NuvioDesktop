package com.nuvio.wayland

/**
 * The colour space to present in, derived from the source.
 *
 * target-colorspace-hint-mode=source, done here because the hint reconfigures a
 * swapchain mpv owns and through the render API the host owns it.
 */
data class TargetColorSpace(
    /** VkColorSpaceKHR for the swapchain. */
    val vk: Int,
    /** pl_color_primaries, for mpv's target. */
    val primaries: Int,
    /** pl_color_transfer, for mpv's target. */
    val transfer: Int,
    /** cd/m^2; 0 leaves it to mpv's own options. */
    val maxLuma: Float = 0f,
    val minLuma: Float = 0f,
) {
    val isHdr: Boolean get() = transfer == PL_TRC_PQ || transfer == PL_TRC_HLG

    companion object {
        // VkColorSpaceKHR; LWJGL names only SRGB_NONLINEAR.
        const val VK_SRGB_NONLINEAR = 0
        const val VK_HDR10_ST2084 = 1000104008
        const val VK_HDR10_HLG = 1000104010

        // Positional, from /usr/include/libplacebo/colorspace.h.
        const val PL_PRIM_BT_709 = 3
        const val PL_PRIM_BT_2020 = 6
        const val PL_TRC_SRGB = 2
        const val PL_TRC_PQ = 12
        const val PL_TRC_HLG = 13

        val SDR = TargetColorSpace(VK_SRGB_NONLINEAR, PL_PRIM_BT_709, PL_TRC_SRGB)

        /** [primaries] and [transfer] are mpv's video-params strings. */
        fun forSource(
            primaries: String?,
            transfer: String?,
            offered: Set<Int>,
        ): TargetColorSpace {
            val wide = primaries == "bt.2020" || primaries == "dci-p3" ||
                primaries == "display-p3"
            val prim = if (wide) PL_PRIM_BT_2020 else PL_PRIM_BT_709
            return when {
                transfer == "pq" && VK_HDR10_ST2084 in offered ->
                    TargetColorSpace(VK_HDR10_ST2084, prim, PL_TRC_PQ)
                transfer == "hlg" && VK_HDR10_HLG in offered ->
                    TargetColorSpace(VK_HDR10_HLG, prim, PL_TRC_HLG)
                // No HDR space offered: sRGB, and mpv tone-maps as before.
                else -> SDR
            }
        }
    }
}
