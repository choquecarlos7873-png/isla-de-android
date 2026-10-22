package com.isladeandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandGeometryTest {
    private val profile = IslandProfile(lengthDp = 120, thicknessDp = 30, gapDp = 8)

    @Test fun nativeCameraEdgeFollowsAllFourRotations() {
        val expected = listOf(
            IslandPlacement(140, 8, 120, 30, IslandEdge.TOP),
            IslandPlacement(8, 340, 30, 120, IslandEdge.LEFT),
            IslandPlacement(140, 762, 120, 30, IslandEdge.BOTTOM),
            IslandPlacement(362, 340, 30, 120, IslandEdge.RIGHT),
        )
        expected.forEachIndexed { rotation, placement ->
            assertEquals(placement, IslandGeometry.place(400, 800, 1f, rotation, profile))
        }
    }

    @Test fun asymmetricCameraDefinesCenterInsteadOfDisplayMidpoint() {
        val camera = CameraBounds(38, 0, 62, 26)
        val result = IslandGeometry.place(400, 800, 1f, 0, profile, listOf(camera))
        // Centering at 50 would cross the left edge; clamp and retain the camera.
        assertEquals(IslandPlacement(0, 0, 120, 38, IslandEdge.TOP), result)
        assertContains(result, camera)
    }

    @Test fun reportedCameraCoordinatesAreAlreadyRotated() {
        val camera = CameraBounds(0, 500, 26, 524)
        val result = IslandGeometry.place(800, 600, 1f, 1, profile, listOf(camera))
        assertEquals(IslandPlacement(0, 452, 38, 120, IslandEdge.LEFT), result)
        assertContains(result, camera)
    }

    @Test fun cameraAtAnotherEdgeOverridesFallbackAndExpandsSize() {
        val camera = CameraBounds(380, 300, 400, 460)
        val result = IslandGeometry.place(400, 800, 1f, 0, profile, listOf(camera))
        assertEquals(IslandPlacement(362, 300, 38, 160, IslandEdge.RIGHT), result)
        assertContains(result, camera)
    }

    @Test fun manualProfileIgnoresDetectedCameraAndUsesDensity() {
        val result = IslandGeometry.place(
            400, 800, 2f, 0, profile.copy(autoCamera = false, offsetDp = 10),
            listOf(CameraBounds(0, 100, 20, 140)),
        )
        assertEquals(IslandPlacement(100, 16, 240, 60, IslandEdge.TOP), result)
    }

    @Test fun nearestEdgeCutoutWinsOverInteriorAndInvalidBounds() {
        val camera = CameraBounds(190, 0, 210, 20)
        val result = IslandGeometry.place(
            400, 800, 1f, 0, profile,
            listOf(CameraBounds(100, 200, 130, 230), CameraBounds(-50, -20, -10, -1), camera),
        )
        assertEquals(IslandPlacement(140, 0, 120, 38, IslandEdge.TOP), result)
    }

    @Test fun smallScreensAndHugeProfilesStayInsideScreen() {
        (0..3).forEach { rotation ->
            val result = IslandGeometry.place(
                20, 10, 4f, rotation,
                profile.copy(lengthDp = Int.MAX_VALUE, thicknessDp = Int.MAX_VALUE),
            )
            assertEquals(20, result.width)
            assertEquals(10, result.height)
            assertInside(result, 20, 10)
        }
    }

    @Test fun extremeOffsetsAndGapsCannotEscapeDisplay() {
        (0..3).forEach { rotation ->
            listOf(Int.MIN_VALUE, Int.MAX_VALUE).forEach { offset ->
                val result = IslandGeometry.place(
                    400, 800, 3f, rotation,
                    profile.copy(offsetDp = offset, gapDp = Int.MAX_VALUE),
                )
                assertInside(result, 400, 800)
            }
        }
    }

    @Test fun cameraStaysCoveredEvenWithExtremeAdjustments() {
        val camera = CameraBounds(180, 0, 220, 45)
        val result = IslandGeometry.place(
            400, 800, 1f, 0,
            profile.copy(offsetDp = Int.MAX_VALUE, gapDp = Int.MAX_VALUE), listOf(camera),
        )
        assertInside(result, 400, 800)
        assertContains(result, camera)
    }

    @Test fun zeroScreenAndInvalidDensityHaveSafeResults() {
        assertEquals(
            IslandPlacement(0, 0, 0, 0, IslandEdge.LEFT),
            IslandGeometry.place(0, 800, 1f, 1, profile),
        )
        assertEquals(
            IslandGeometry.place(400, 800, 1f, 0, profile),
            IslandGeometry.place(400, 800, Float.NaN, 0, profile),
        )
    }

    private fun assertInside(result: IslandPlacement, width: Int, height: Int) {
        assertTrue("left", result.x >= 0)
        assertTrue("top", result.y >= 0)
        assertTrue("right", result.x.toLong() + result.width <= width)
        assertTrue("bottom", result.y.toLong() + result.height <= height)
        assertTrue("width", result.width > 0)
        assertTrue("height", result.height > 0)
    }

    private fun assertContains(result: IslandPlacement, camera: CameraBounds) {
        assertTrue(result.x <= camera.left)
        assertTrue(result.y <= camera.top)
        assertTrue(result.x + result.width >= camera.right)
        assertTrue(result.y + result.height >= camera.bottom)
    }
}
