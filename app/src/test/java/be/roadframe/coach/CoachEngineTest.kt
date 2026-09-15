package be.roadframe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEngineTest {
    private val engine = CoachEngine()
    private val neutralStats = FrameStats(meanLuma = 0.48f)
    private val level = Pose(rollDeg = 0f, pitchDeg = 0f, headingDeg = 0f, timestampNanos = 0L)

    private fun car(box: NormalizedBox, imageAspect: Float = 2.2f) =
        DetectedSubject(box, VehicleKind.CAR, "car", 0.9f, imageAspect)

    private fun input(
        subject: DetectedSubject?,
        category: ShotCategory = ShotCategory.FRONT_THREE_QUARTER,
        structure: CoachStructure = CoachStructure.ROADFRAME,
        pose: Pose? = level,
        viewingAngle: Float? = null,
        stats: FrameStats = neutralStats,
        zoom: Float = 3f,
        portrait: Boolean = false,
        nowMs: Long = 10_000L
    ) = CoachEngine.Input(category, structure, subject, pose, viewingAngle, stats, zoom, portrait, nowMs)

    /** A car sitting exactly on the target of the category. */
    private fun onTarget(category: ShotCategory, viewingAngle: Float? = null, portrait: Boolean = false): DetectedSubject {
        val seed = car(NormalizedBox(0.2f, 0.4f, 0.8f, 0.7f))
        val target = engine.targetFor(input(seed, category, viewingAngle = viewingAngle, portrait = portrait))!!
        return seed.copy(box = target)
    }

    @Test
    fun noDetectionSearches() {
        val guidance = engine.evaluate(input(null))
        assertTrue(guidance.searching)
        assertEquals(Instruction.FIND_CAR.word, guidance.headline)
        assertFalse(guidance.shoot)
    }

    @Test
    fun perfectFrameSaysPerfectThenShoot() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val first = engine.evaluate(input(subject, viewingAngle = -45f, nowMs = 1_000L))
        assertTrue(first.shoot)
        assertEquals(Instruction.PERFECT.word, first.headline)
        assertTrue(first.score >= 85)
        val later = engine.evaluate(input(subject, viewingAngle = -45f, nowMs = 2_000L))
        assertEquals(Instruction.SHOOT.word, later.headline)
    }

    @Test
    fun grossRollComesFirstInRoadFrameOrder() {
        val subject = car(NormalizedBox(0.35f, 0.45f, 0.65f, 0.62f))
        val guidance = engine.evaluate(input(subject, pose = level.copy(rollDeg = 9f)))
        assertEquals(Rule.LEVEL_GROSS, guidance.primary?.rule)
        assertEquals(Instruction.LEVEL.word, guidance.headline)
        assertEquals(9f, guidance.arrows.rollDeg)
    }

    @Test
    fun smallRollWaitsUntilSizeIsFixed() {
        val small = car(NormalizedBox(0.35f, 0.45f, 0.65f, 0.62f))
        val guidance = engine.evaluate(input(small, pose = level.copy(rollDeg = 3f)))
        assertEquals(Rule.SIZE, guidance.primary?.rule)
        assertTrue(guidance.secondary.any { it.rule == Rule.LEVEL })
    }

    @Test
    fun handoffStructureAimsBeforeWalking() {
        val offCentre = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f).let {
            it.copy(box = it.box.shifted(-0.15f, 0f))
        }
        val roadframe = CoachEngine().evaluate(input(offCentre, viewingAngle = 0f, structure = CoachStructure.ROADFRAME))
        val handoff = CoachEngine().evaluate(input(offCentre, viewingAngle = 0f, structure = CoachStructure.HANDOFF))
        assertEquals(Rule.ANGLE, roadframe.primary?.rule)
        assertEquals(Rule.PAN, handoff.primary?.rule)
    }

    @Test
    fun smallCarWithWideLensSaysZoom() {
        val subject = car(NormalizedBox(0.35f, 0.45f, 0.65f, 0.62f))
        val guidance = engine.evaluate(input(subject, zoom = 1f))
        assertEquals(Instruction.ZOOM, guidance.primary?.instruction)
        assertEquals("ZOOM 3×", guidance.headline)
        assertEquals(1, guidance.arrows.size)
    }

    @Test
    fun smallCarAtRecommendedZoomSaysCloser() {
        val subject = car(NormalizedBox(0.35f, 0.45f, 0.65f, 0.62f))
        val guidance = engine.evaluate(input(subject, zoom = 3f))
        assertEquals(Instruction.CLOSER, guidance.primary?.instruction)
    }

    @Test
    fun croppedCarSaysBack() {
        val subject = car(NormalizedBox(0.01f, 0.28f, 0.99f, 0.84f))
        val guidance = engine.evaluate(input(subject))
        assertEquals(Rule.CROPPED, guidance.primary?.rule)
        assertEquals(Instruction.BACK.word, guidance.headline)
        assertEquals(-1, guidance.arrows.size)
    }

    @Test
    fun walkLeftWhenAngleIsShortOfTarget() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val guidance = engine.evaluate(input(subject, viewingAngle = -20f))
        assertEquals(Instruction.WALK_RIGHT, guidance.primary?.instruction)
        assertEquals("WALK RIGHT 25°", guidance.headline)
        assertEquals(-25f, guidance.arrows.orbitDeg)
    }

    @Test
    fun nearestOfSeveralTargetsIsChosen() {
        val subject = onTarget(ShotCategory.SIDE_PROFILE, viewingAngle = 90f)
        val guidance = engine.evaluate(input(subject, ShotCategory.SIDE_PROFILE, viewingAngle = 75f))
        assertEquals(Instruction.WALK_LEFT, guidance.primary?.instruction)
        assertEquals(15f, guidance.arrows.orbitDeg)
    }

    @Test
    fun uncalibratedSideShotUsesBoxShape() {
        val tall = onTarget(ShotCategory.SIDE_PROFILE).copy(imageAspect = 1.4f)
        val guidance = engine.evaluate(input(tall, ShotCategory.SIDE_PROFILE))
        assertEquals(Instruction.WALK_AROUND, guidance.primary?.instruction)
        val wide = tall.copy(imageAspect = 2.9f)
        val settled = engine.evaluate(input(wide, ShotCategory.SIDE_PROFILE))
        assertNull(settled.primary)
    }

    @Test
    fun pointingDownSaysLower() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val guidance = engine.evaluate(input(subject, viewingAngle = -45f, pose = level.copy(pitchDeg = 14f)))
        assertEquals(Instruction.LOWER, guidance.primary?.instruction)
        assertTrue(guidance.arrows.lower)
    }

    @Test
    fun sceneShotDoesNotAskForLowCamera() {
        val subject = onTarget(ShotCategory.SCENE, viewingAngle = -45f)
        val guidance = engine.evaluate(input(subject, ShotCategory.SCENE, viewingAngle = -45f, pose = level.copy(pitchDeg = 14f)))
        assertNull(guidance.primary)
    }

    @Test
    fun leadRoomShiftsTargetAwayFromNose() {
        val seed = car(NormalizedBox(0.2f, 0.4f, 0.8f, 0.7f))
        val noseLeft = engine.targetFor(input(seed, viewingAngle = -45f))!!
        val noseRight = engine.targetFor(input(seed, viewingAngle = 45f))!!
        val unknown = engine.targetFor(input(seed, viewingAngle = null))!!
        assertTrue(noseLeft.centerX > unknown.centerX)
        assertTrue(noseRight.centerX < unknown.centerX)
    }

    @Test
    fun hysteresisKeepsInstructionUntilClearlyFixed() {
        val seed = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val far = seed.copy(box = seed.box.shifted(-0.08f, 0f))
        assertEquals(Rule.PAN, engine.evaluate(input(far, viewingAngle = -45f)).primary?.rule)
        val nearly = seed.copy(box = seed.box.shifted(-0.04f, 0f))
        assertEquals(Rule.PAN, engine.evaluate(input(nearly, viewingAngle = -45f)).primary?.rule)
        val fixed = seed.copy(box = seed.box.shifted(-0.02f, 0f))
        assertNull(engine.evaluate(input(fixed, viewingAngle = -45f)).primary)
        // Starting fresh, 0.04 is inside the enter threshold and never complains.
        val fresh = CoachEngine()
        assertNull(fresh.evaluate(input(nearly, viewingAngle = -45f)).primary)
    }

    @Test
    fun resolvedInstructionShowsGoodBeat() {
        val seed = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val far = seed.copy(box = seed.box.shifted(-0.08f, 0f))
        engine.evaluate(input(far, viewingAngle = -45f, pose = level.copy(rollDeg = 3f), nowMs = 1_000L))
        val afterFix = engine.evaluate(input(seed, viewingAngle = -45f, pose = level.copy(rollDeg = 3f), nowMs = 1_100L))
        assertEquals("GOOD", afterFix.headline)
        assertEquals(Rule.LEVEL, afterFix.primary?.rule)
        val later = engine.evaluate(input(seed, viewingAngle = -45f, pose = level.copy(rollDeg = 3f), nowMs = 2_000L))
        assertEquals(Instruction.LEVEL.word, later.headline)
    }

    @Test
    fun noGoodBeatWhenTheNextWordIsTheSame() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        engine.evaluate(input(subject, viewingAngle = -45f, pose = level.copy(rollDeg = 9f), nowMs = 1_000L))
        val stillTilted = engine.evaluate(input(subject, viewingAngle = -45f, pose = level.copy(rollDeg = 4f), nowMs = 1_100L))
        assertEquals(Rule.LEVEL, stillTilted.primary?.rule)
        assertEquals(Instruction.LEVEL.word, stillTilted.headline)
    }

    @Test
    fun rollingShotWithoutSelectionExplainsTheBurst() {
        val guidance = engine.evaluate(input(null, ShotCategory.ROLLING))
        assertEquals(Instruction.FRAME_IT.word, guidance.headline)
        assertTrue(guidance.why.contains("Burst"))
    }

    @Test
    fun aimDirectionsMatchScreenSpace() {
        val seed = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val carTooFarLeft = seed.copy(box = seed.box.shifted(-0.1f, 0f))
        val guidance = engine.evaluate(input(carTooFarLeft, viewingAngle = -45f))
        assertEquals(Instruction.AIM_LEFT, guidance.primary?.instruction)
        assertEquals(-1, guidance.arrows.panX)
        val carTooLow = seed.copy(box = seed.box.shifted(0f, 0.1f))
        val tilt = CoachEngine().evaluate(input(carTooLow, viewingAngle = -45f))
        assertEquals(Instruction.AIM_DOWN, tilt.primary?.instruction)
        assertEquals(1, tilt.arrows.panY)
    }

    @Test
    fun motorcycleSizingUsesHeight() {
        val motorcycle = DetectedSubject(NormalizedBox(0.43f, 0.46f, 0.57f, 0.64f), VehicleKind.MOTORCYCLE, "motorcycle", 0.9f, 0.8f)
        val guidance = engine.evaluate(input(motorcycle, viewingAngle = -45f))
        assertEquals(Rule.SIZE, guidance.primary?.rule)
    }

    @Test
    fun blownHighlightsSayDarker() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val guidance = engine.evaluate(input(subject, viewingAngle = -45f, stats = FrameStats(meanLuma = 0.82f, highlightFraction = 0.2f)))
        assertEquals(Instruction.DARKER, guidance.primary?.instruction)
        assertFalse(guidance.shoot)
    }

    @Test
    fun deepShadowSaysFindLight() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val guidance = engine.evaluate(input(subject, viewingAngle = -45f, stats = FrameStats(meanLuma = 0.1f, shadowFraction = 0.5f)))
        assertEquals(Instruction.FIND_LIGHT, guidance.primary?.instruction)
    }

    @Test
    fun clutterSuggestsCleanerSide() {
        val subject = onTarget(ShotCategory.FRONT_THREE_QUARTER, viewingAngle = -45f)
        val stats = FrameStats(meanLuma = 0.5f, leftEdgeDensity = 0.05f, rightEdgeDensity = 0.3f)
        val guidance = engine.evaluate(input(subject, viewingAngle = -45f, stats = stats))
        assertEquals(Instruction.STEP_LEFT, guidance.primary?.instruction)
    }

    @Test
    fun detailShotWithoutSelectionAsksToFrame() {
        val guidance = engine.evaluate(input(null, ShotCategory.WHEEL))
        assertFalse(guidance.searching)
        assertEquals(Instruction.FRAME_IT.word, guidance.headline)
        assertFalse(guidance.shoot)
    }

    @Test
    fun detailShotWithGoodSelectionCanShoot() {
        val subject = onTarget(ShotCategory.WHEEL)
        val guidance = engine.evaluate(input(subject, ShotCategory.WHEEL))
        assertTrue(guidance.shoot)
    }

    @Test
    fun rollingShotIgnoresLevel() {
        val guidance = engine.evaluate(input(null, ShotCategory.ROLLING, pose = level.copy(rollDeg = 12f)))
        assertNull(guidance.primary)
        assertNull(guidance.arrows.rollDeg)
        assertFalse(guidance.shoot)
    }

    @Test
    fun portraitTargetIsWider() {
        val seed = car(NormalizedBox(0.2f, 0.4f, 0.8f, 0.7f))
        val landscape = engine.targetFor(input(seed, portrait = false))!!
        val portrait = engine.targetFor(input(seed, portrait = true))!!
        assertTrue(portrait.width > landscape.width)
        assertNotNull(engine.targetFor(input(seed)))
    }
}
