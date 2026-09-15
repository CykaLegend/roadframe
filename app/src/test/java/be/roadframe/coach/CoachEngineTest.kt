package be.roadframe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEngineTest {
    private val engine = CoachEngine()
    private val neutralStats = FrameStats(meanLuma = 0.48f)

    @Test
    fun noDetectionAsksForVehicle() {
        val advice = engine.evaluate(null, ShotMode.BALANCED, 0f, neutralStats)
        assertEquals(AdviceAction.FIND_SUBJECT, advice.action)
    }

    @Test
    fun detailModeAsksForManualSelection() {
        val advice = engine.evaluate(
            null,
            ShotMode.DETAIL,
            0f,
            neutralStats,
            detailSelectionPending = true
        )
        assertEquals(AdviceAction.SELECT_DETAIL, advice.action)
    }

    @Test
    fun rollCorrectionHasPriority() {
        val subject = car(NormalizedBox(0.15f, 0.36f, 0.85f, 0.75f))
        val advice = engine.evaluate(subject, ShotMode.BALANCED, 7f, neutralStats)
        assertEquals(AdviceAction.ROTATE_LEFT, advice.action)
    }

    @Test
    fun smallCarRequestsCloserPosition() {
        val subject = car(NormalizedBox(0.35f, 0.45f, 0.65f, 0.62f))
        val advice = engine.evaluate(subject, ShotMode.BALANCED, 0f, neutralStats)
        assertEquals(AdviceAction.STEP_CLOSER, advice.action)
    }

    @Test
    fun croppedCarRequestsMoreDistance() {
        val subject = car(NormalizedBox(0.01f, 0.28f, 0.99f, 0.84f))
        val advice = engine.evaluate(subject, ShotMode.BALANCED, 0f, neutralStats)
        assertEquals(AdviceAction.STEP_BACK, advice.action)
    }

    @Test
    fun centeredTargetIsReady() {
        val seed = car(NormalizedBox(0.14f, 0.39f, 0.86f, 0.75f))
        val target = engine.targetFor(seed, ShotMode.BALANCED)
        val advice = engine.evaluate(seed.copy(box = target), ShotMode.BALANCED, 0f, neutralStats)
        assertEquals(AdviceAction.HOLD, advice.action)
        assertTrue(advice.ready)
        assertTrue(advice.score >= 90)
    }

    @Test
    fun cinematicModeMovesCarIntoNegativeSpaceLayout() {
        val seed = car(NormalizedBox(0.20f, 0.42f, 0.80f, 0.72f))
        val target = engine.targetFor(seed, ShotMode.CINEMATIC)
        val sameSizeButCentered = seed.copy(
            box = NormalizedBox.centered(0.55f, target.centerY, target.width, target.height)
        )
        val advice = engine.evaluate(sameSizeButCentered, ShotMode.CINEMATIC, 0f, neutralStats)
        assertEquals(AdviceAction.SUBJECT_LEFT, advice.action)
    }

    @Test
    fun motorcycleSizingUsesHeight() {
        val motorcycle = DetectedSubject(
            NormalizedBox(0.43f, 0.46f, 0.57f, 0.64f),
            VehicleKind.MOTORCYCLE,
            "motorcycle",
            0.9f
        )
        val advice = engine.evaluate(motorcycle, ShotMode.BALANCED, 0f, neutralStats)
        assertEquals(AdviceAction.STEP_CLOSER, advice.action)
    }

    @Test
    fun blownHighlightsAreExplained() {
        val seed = car(NormalizedBox(0.14f, 0.39f, 0.86f, 0.75f))
        val target = engine.targetFor(seed, ShotMode.BALANCED)
        val advice = engine.evaluate(
            seed.copy(box = target),
            ShotMode.BALANCED,
            0f,
            FrameStats(meanLuma = 0.82f, highlightFraction = 0.2f)
        )
        assertEquals(AdviceAction.LOWER_EXPOSURE, advice.action)
    }

    private fun car(box: NormalizedBox) = DetectedSubject(
        box,
        VehicleKind.CAR,
        "car",
        0.9f
    )
}
