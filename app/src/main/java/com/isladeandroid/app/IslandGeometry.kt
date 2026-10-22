package com.isladeandroid.app

import kotlin.math.roundToInt

enum class IslandEdge { TOP, LEFT, RIGHT, BOTTOM }

data class IslandPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val edge: IslandEdge,
)

data class IslandProfile(
    val lengthDp: Int = 124,
    val thicknessDp: Int = 32,
    val offsetDp: Int = 0,
    val gapDp: Int = 8,
    val autoCamera: Boolean = true,
)

/** A display cutout in absolute, current-rotation screen coordinates. */
data class CameraBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Pure geometry shared by the overlay and its preview; no Android dependencies. */
object IslandGeometry {
    /**
     * [rotation] uses Surface.ROTATION_* values (0, 1, 2, 3).
     * Positive offset moves right on horizontal edges and down on vertical edges.
     * A detected camera stays covered, including when the requested gap or offset
     * would otherwise expose part of it. The result always fits the screen.
     */
    fun place(
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        rotation: Int,
        profile: IslandProfile,
        cutouts: List<CameraBounds> = emptyList(),
    ): IslandPlacement {
        val screenW = screenWidth.coerceAtLeast(0)
        val screenH = screenHeight.coerceAtLeast(0)
        val nativeEdge = when (((rotation % 4) + 4) % 4) {
            1 -> IslandEdge.LEFT
            2 -> IslandEdge.BOTTOM
            3 -> IslandEdge.RIGHT
            else -> IslandEdge.TOP
        }
        if (screenW == 0 || screenH == 0) {
            return IslandPlacement(0, 0, 0, 0, nativeEdge)
        }
        val scale = if (density.isFinite() && density > 0f) density.toDouble() else 1.0
        fun pixels(dp: Int): Double = dp.toDouble() * scale

        val camera = if (profile.autoCamera) {
            cutouts.mapNotNull { raw ->
                val bounds = CameraBounds(
                    raw.left.coerceIn(0, screenW), raw.top.coerceIn(0, screenH),
                    raw.right.coerceIn(0, screenW), raw.bottom.coerceIn(0, screenH),
                )
                if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) null
                else {
                    val distances = listOf(
                        IslandEdge.TOP to bounds.top,
                        IslandEdge.LEFT to bounds.left,
                        IslandEdge.RIGHT to screenW - bounds.right,
                        IslandEdge.BOTTOM to screenH - bounds.bottom,
                    )
                    val nearest = distances.minWithOrNull(
                        compareBy<Pair<IslandEdge, Int>> { it.second }
                            .thenBy { if (it.first == nativeEdge) 0 else 1 },
                    )!!
                    CameraCandidate(bounds, nearest.first, nearest.second)
                }
            }.minWithOrNull(
                compareBy<CameraCandidate> { it.distance }
                    .thenBy { it.area }
                    .thenBy { if (it.edge == nativeEdge) 0 else 1 },
            )
        } else null

        val edge = camera?.edge ?: nativeEdge
        val horizontal = edge == IslandEdge.TOP || edge == IslandEdge.BOTTOM
        val width = pixels(if (horizontal) profile.lengthDp else profile.thicknessDp)
            .roundToInt().coerceIn(1, screenW)
        val height = pixels(if (horizontal) profile.thicknessDp else profile.lengthDp)
            .roundToInt().coerceIn(1, screenH)
        val offset = pixels(profile.offsetDp)
        val gap = pixels(profile.gapDp).coerceAtLeast(0.0)
        val centerX = camera?.bounds?.let { (it.left.toDouble() + it.right) / 2.0 }
            ?: (screenW / 2.0)
        val centerY = camera?.bounds?.let { (it.top.toDouble() + it.bottom) / 2.0 }
            ?: (screenH / 2.0)

        val x = when (edge) {
            IslandEdge.TOP, IslandEdge.BOTTOM -> centerX - width / 2.0 + offset
            IslandEdge.LEFT -> gap
            IslandEdge.RIGHT -> screenW.toDouble() - width - gap
        }.roundToInt().coerceIn(0, screenW - width)
        val y = when (edge) {
            IslandEdge.LEFT, IslandEdge.RIGHT -> centerY - height / 2.0 + offset
            IslandEdge.TOP -> gap
            IslandEdge.BOTTOM -> screenH.toDouble() - height - gap
        }.roundToInt().coerceIn(0, screenH - height)

        val bounds = camera?.bounds
            ?: return IslandPlacement(x, y, width, height, edge)
        val left = minOf(x, bounds.left)
        val top = minOf(y, bounds.top)
        val right = maxOf(x + width, bounds.right)
        val bottom = maxOf(y + height, bounds.bottom)
        return IslandPlacement(left, top, right - left, bottom - top, edge)
    }

    private data class CameraCandidate(
        val bounds: CameraBounds,
        val edge: IslandEdge,
        val distance: Int,
    ) {
        val area: Long get() = (bounds.right - bounds.left).toLong() * (bounds.bottom - bounds.top)
    }
}
