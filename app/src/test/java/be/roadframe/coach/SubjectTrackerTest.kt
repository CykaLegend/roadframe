package be.roadframe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectTrackerTest {
    private val tracker = SubjectTracker()
    private val box = NormalizedBox(0.3f, 0.3f, 0.7f, 0.6f)
    private fun pose(heading: Float, pitch: Float = 0f) = Pose(0f, pitch, heading, 0L)

    @Test
    fun firstDetectionIsUsedAsIs() {
        tracker.onDetection(box, VehicleKind.CAR, "car", 0.9f, 2f, pose(100f), 60f, 45f, 1_000L)
        val current = tracker.current(pose(100f), 60f, 45f, 1_050L)
        assertNotNull(current)
        assertEquals(box, current!!.box)
    }

    @Test
    fun boxFollowsTheGyroBetweenDetections() {
        tracker.onDetection(box, VehicleKind.CAR, "car", 0.9f, 2f, pose(100f), 60f, 45f, 1_000L)
        val turnedRight = tracker.current(pose(110f), 60f, 45f, 1_100L)!!
        assertTrue(turnedRight.box.centerX < box.centerX)
        val tiltedDown = tracker.current(pose(100f, pitch = 10f), 60f, 45f, 1_100L)!!
        assertTrue(tiltedDown.box.centerY < box.centerY)
    }

    @Test
    fun secondDetectionBlendsFromTheCarriedAnchor() {
        tracker.onDetection(box, VehicleKind.CAR, "car", 0.9f, 2f, pose(100f), 60f, 45f, 1_000L)
        // The phone turned 10° right, the detector reports the box exactly where prediction says.
        val expected = PoseMath.predictBox(box, pose(100f), pose(110f), 60f, 45f)
        tracker.onDetection(expected, VehicleKind.CAR, "car", 0.9f, 2f, pose(110f), 60f, 45f, 1_100L)
        val current = tracker.current(pose(110f), 60f, 45f, 1_110L)!!
        assertEquals(expected.centerX, current.box.centerX, 0.0005f)
    }

    @Test
    fun jitterIsDamped() {
        tracker.onDetection(box, VehicleKind.CAR, "car", 0.9f, 2f, pose(100f), 60f, 45f, 1_000L)
        tracker.onDetection(box.shifted(0.1f, 0f), VehicleKind.CAR, "car", 0.9f, 2f, pose(100f), 60f, 45f, 1_100L)
        val current = tracker.current(pose(100f), 60f, 45f, 1_110L)!!
        assertEquals(box.centerX + 0.1f * SubjectTracker.BLEND, current.box.centerX, 0.0005f)
    }

    @Test
    fun subjectIsLostAfterSilence() {
        tracker.onDetection(box, VehicleKind.CAR, "car", 0.9f, 2f, pose(100f), 60f, 45f, 1_000L)
        assertNotNull(tracker.current(pose(100f), 60f, 45f, 1_800L))
        assertNull(tracker.current(pose(100f), 60f, 45f, 2_000L))
    }

    @Test
    fun worksWithoutSensors() {
        tracker.onDetection(box, VehicleKind.CAR, "car", 0.9f, 2f, null, 60f, 45f, 1_000L)
        assertEquals(box, tracker.current(null, 60f, 45f, 1_100L)!!.box)
    }
}
