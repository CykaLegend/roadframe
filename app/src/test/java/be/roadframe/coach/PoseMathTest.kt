package be.roadframe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PoseMathTest {
    private val g = 9.81f

    /** Gravity (pointing up, Android convention) in device axes after rotating the phone clockwise by [degrees]. */
    private fun gravityAfterClockwiseRoll(degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        // Device x axis in world = (cos r, -sin r); y axis = (sin r, cos r); world up = (0, 1).
        return (-g * sin(r)).toFloat() to (g * cos(r)).toFloat()
    }

    @Test
    fun uprightPortraitIsLevel() {
        assertEquals(0f, PoseMath.rollDegrees(0f, g, 0), 0.01f)
    }

    @Test
    fun clockwiseRollIsPositive() {
        val (x, y) = gravityAfterClockwiseRoll(10f)
        assertEquals(10f, PoseMath.rollDegrees(x, y, 0), 0.05f)
        val (x2, y2) = gravityAfterClockwiseRoll(-7f)
        assertEquals(-7f, PoseMath.rollDegrees(x2, y2, 0), 0.05f)
    }

    @Test
    fun landscapeRotation90IsLevelWhenHorizonIsLevel() {
        // Device turned 90° counter-clockwise (Surface.ROTATION_90): device +x points up.
        assertEquals(0f, PoseMath.rollDegrees(g, 0f, 90), 0.01f)
    }

    @Test
    fun landscapeRotation270IsLevelWhenHorizonIsLevel() {
        // Device turned 90° clockwise (Surface.ROTATION_270): device +x points down.
        assertEquals(0f, PoseMath.rollDegrees(-g, 0f, 270), 0.01f)
    }

    @Test
    fun landscapeRollKeepsSign() {
        // ROTATION_90 phone, then rotated a further 5° clockwise: total device roll is -85° from portrait.
        val (x, y) = gravityAfterClockwiseRoll(-85f)
        assertEquals(5f, PoseMath.rollDegrees(x, y, 90), 0.05f)
    }

    @Test
    fun pitchIsZeroUprightAndPositivePointingDown() {
        assertEquals(0f, PoseMath.pitchDegrees(0f, g, 0f), 0.01f)
        assertEquals(90f, PoseMath.pitchDegrees(0f, 0f, g), 0.01f)
        val tilted = PoseMath.pitchDegrees(0f, (g * cos(Math.toRadians(20.0))).toFloat(), (g * sin(Math.toRadians(20.0))).toFloat())
        assertEquals(20f, tilted, 0.05f)
    }

    @Test
    fun wrapKeepsAnglesInHalfTurn() {
        assertEquals(-170f, PoseMath.wrap(190f), 0.001f)
        assertEquals(170f, PoseMath.wrap(-190f), 0.001f)
        assertEquals(180f, PoseMath.wrap(180f), 0.001f)
        assertEquals(0f, PoseMath.wrap(720f), 0.001f)
    }

    @Test
    fun headingFollowsCameraAxis() {
        // Identity rotation: device axes = world axes, camera (-Z) points down into the ground.
        // Rotate the phone upright (x axis stays east, y up, z south): camera points north.
        val upright = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        assertEquals(0f, PoseMath.headingDegrees(upright), 0.01f)
        // Turned to face east: device x = south, y = up, z = west, camera points east.
        val east = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f
        )
        assertEquals(90f, PoseMath.headingDegrees(east), 0.01f)
    }

    @Test
    fun turningRightShiftsSceneLeft() {
        val anchor = NormalizedBox(0.4f, 0.4f, 0.6f, 0.6f)
        val then = Pose(0f, 0f, 100f, 0L)
        val now = Pose(0f, 0f, 110f, 1L)
        val predicted = PoseMath.predictBox(anchor, then, now, 60f, 45f)
        assertTrue(predicted.centerX < anchor.centerX)
        assertEquals(anchor.centerY, predicted.centerY, 0.0001f)
        // 10° of a 60° field of view: tan(10°) / (2 tan(30°)) = 0.1527 of the width.
        assertEquals(-0.1527f, predicted.centerX - anchor.centerX, 0.002f)
    }

    @Test
    fun pointingDownShiftsSceneUp() {
        val anchor = NormalizedBox(0.4f, 0.4f, 0.6f, 0.6f)
        val then = Pose(0f, 0f, 100f, 0L)
        val now = Pose(0f, 8f, 100f, 1L)
        val predicted = PoseMath.predictBox(anchor, then, now, 60f, 45f)
        assertTrue(predicted.centerY < anchor.centerY)
        assertEquals(anchor.centerX, predicted.centerX, 0.0001f)
    }

    @Test
    fun headingWrapsAcrossNorth() {
        val anchor = NormalizedBox(0.4f, 0.4f, 0.6f, 0.6f)
        val predicted = PoseMath.predictBox(anchor, Pose(0f, 0f, 358f, 0L), Pose(0f, 0f, 2f, 1L), 60f, 45f)
        assertTrue(predicted.centerX < anchor.centerX)
        assertTrue(predicted.centerX > anchor.centerX - 0.1f)
    }

    @Test
    fun zoomNarrowsTheVisibleField() {
        val wide = PoseMath.visibleFovDegrees(70f, 1f, 1f)
        val tele = PoseMath.visibleFovDegrees(70f, 1f, 3f)
        assertEquals(70f, wide, 0.01f)
        assertTrue(tele < 30f)
    }

    @Test
    fun portraitFillCentreCropsTheWidth() {
        val (fovX, fovY) = PoseMath.viewFov(70f, 55f, 1080, 2340, portrait = true, zoomRatio = 1f)
        assertEquals(70f, fovY, 0.01f)
        assertTrue(fovX < 55f)
        val (landscapeX, _) = PoseMath.viewFov(70f, 55f, 2340, 1080, portrait = false, zoomRatio = 1f)
        assertEquals(70f, landscapeX, 0.01f)
    }
}
