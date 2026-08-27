package com.nuvio.wayland

/**
 * The colour space to present in, derived from the source.
 *
 * This is `--target-colorspace-hint-mode=source` done by hand, because through
 * the render API mpv cannot do it itself: the hint works by reconfiguring a
 * swapchain mpv owns, and here the host owns it. So the host reads what the
 * file is, picks a surface colour space that can carry it, and tells mpv what
 * it picked (see Mpv.VulkanFrame's colour space fields).
 *
 * Nothing is fixed to one format. A PQ file gets PQ, an HLG file gets HLG, an
 * SDR file stays sRGB, and a surface that offers neither falls back to sRGB
 * with mpv tone-mapping as it always did.
 */
data class TargetColorSpace(
    /** VkColorSpaceKHR for the swapchain. */
    val vk: Int,
    /** pl_color_primaries, for mpv's target. */
    val primaries: Int,
    /** pl_color_transfer, for mpv's target. */
    val transfer: Int,
    /** Display peak in cd/m^2; 0 lets mpv decide from its own options. */
    val maxLuma: Float = 0f,
    val minLuma: Float = 0f,
) {
    val isHdr: Boolean get() = transfer == PL_TRC_PQ || transfer == PL_TRC_HLG

    companion object {
        // VkColorSpaceKHR. LWJGL names only SRGB_NONLINEAR of these.
        const val VK_SRGB_NONLINEAR = 0
        const val VK_HDR10_ST2084 = 1000104008
        const val VK_HDR10_HLG = 1000104010

        // libplacebo enum values, from /usr/include/libplacebo/colorspace.h --
        // the same header this mpv builds against. They are positional, so they
        // are read from there rather than guessed, and this is the only place
        // in the host that knows them.
        const val PL_PRIM_BT_709 = 3
        const val PL_PRIM_BT_2020 = 6
        const val PL_TRC_SRGB = 2
        const val PL_TRC_PQ = 12
        const val PL_TRC_HLG = 13

        /** What to use when there is no file, or nothing HDR about it. */
        val SDR = TargetColorSpace(VK_SRGB_NONLINEAR, PL_PRIM_BT_709, PL_TRC_SRGB)

        /**
         * Choose a target for a source, limited to what the surface offers.
         *
         * [primaries] and [transfer] are mpv's own video-params strings, so the
         * vocabulary is mpv's rather than one invented here.
         */
        fun forSource(
            primaries: String?,
            transfer: String?,
            offered: Set<Int>,
        ): TargetColorSpace {
            val wide = primaries == "bt.2020" || primaries == "dci-p3" ||
                primaries == "display-p3"
            val target = when {
                transfer == "pq" && VK_HDR10_ST2084 in offered ->
                    TargetColorSpace(
                        VK_HDR10_ST2084,
                        if (wide) PL_PRIM_BT_2020 else PL_PRIM_BT_709,
                        PL_TRC_PQ,
                    )
                transfer == "hlg" && VK_HDR10_HLG in offered ->
                    TargetColorSpace(
                        VK_HDR10_HLG,
                        if (wide) PL_PRIM_BT_2020 else PL_PRIM_BT_709,
                        PL_TRC_HLG,
                    )
                // Includes HDR sources on a surface that offers no HDR space:
                // stay sRGB and let mpv tone-map, which is what it did before
                // any of this and is still the right answer there.
                else -> SDR
            }
            return target
        }
    }
}
