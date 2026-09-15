package be.roadframe.coach

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure, deterministic coaching rules with hysteresis. The camera and sensors produce numbers;
 * this class turns them into one spoken instruction, the reason behind it, and the arrows.
 *
 * Every rule has an *enter* threshold and a smaller *exit* threshold, so an instruction appears
 * as soon as the error is clearly there and only disappears once it is clearly fixed. That, not
 * a frame counter, is what stops the headline from flickering. The engine is Android-free and
 * every rule is unit-tested.
 */
class CoachEngine {

    data class Input(
        val category: ShotCategory,
        val structure: CoachStructure,
        /** Predicted subject box in screen-normalized coordinates, or the manual detail box. */
        val subject: DetectedSubject?,
        val pose: Pose?,
        /** Viewing angle from the nose calibration, or null when not calibrated. */
        val viewingAngle: Float?,
        val stats: FrameStats,
        val zoomRatio: Float = 1f,
        val portrait: Boolean = true,
        val nowMs: Long
    )

    private class Assessment(
        val rule: Rule,
        /** Signed-free size of the problem; positive means "there is a problem". */
        val magnitude: Float,
        val enter: Float,
        val exit: Float,
        val cue: Cue,
        val arrows: (Arrows) -> Arrows = { it }
    )

    private val active = LinkedHashSet<Rule>()
    private var primaryRule: Rule? = null
    private var primaryInstruction: Instruction? = null
    private var goodUntilMs = 0L
    private var shootSinceMs = 0L

    fun reset() {
        active.clear()
        primaryRule = null
        primaryInstruction = null
        goodUntilMs = 0L
        shootSinceMs = 0L
    }

    /** Which rules currently hold the coach back; exposed for tests. */
    val activeRules: Set<Rule> get() = active

    fun targetFor(input: Input): NormalizedBox? {
        val subject = input.subject ?: return null
        val geometry = input.category.geometry
        val fill = if (input.portrait) min(0.92f, geometry.fill * 1.2f) else geometry.fill
        var centerX = geometry.centerX
        val angle = input.viewingAngle
        if (geometry.leadRoom && angle != null && abs(angle) > 20f && abs(angle) < 160f) {
            centerX += if (angle < 0f) LEAD_SHIFT else -LEAD_SHIFT
        }
        val aspect = subject.box.aspectRatio.coerceIn(0.45f, 3.4f)
        val width: Float
        val height: Float
        if (subject.kind == VehicleKind.MOTORCYCLE && geometry.tracksVehicle) {
            height = fill * MOTORCYCLE_HEIGHT_FACTOR
            width = (height * aspect).coerceAtMost(0.9f)
        } else {
            width = fill
            height = (width / aspect).coerceAtMost(0.88f)
        }
        return NormalizedBox.centered(centerX, geometry.centerY, width, height)
    }

    fun evaluate(input: Input): Guidance {
        val geometry = input.category.geometry
        val subject = input.subject
        val target = targetFor(input)
        val assessments = ArrayList<Assessment>(10)

        if (geometry.usesLevel) input.pose?.let { assessLevel(it, assessments) }
        if (geometry.lowCamera) input.pose?.let { assessPitch(it, assessments) }
        if (subject != null && target != null) {
            assessCropped(subject.box, assessments)
            if (geometry.tracksVehicle) assessAngle(input, subject, assessments)
            assessSize(input, subject, target, assessments)
            assessAim(subject.box, target, assessments)
        }
        assessExposure(input.stats, subject != null, assessments)
        if (geometry.tracksVehicle && subject != null) assessBackground(input.stats, assessments)

        // Hysteresis: a rule joins when it crosses its enter threshold and leaves only below exit.
        val seen = HashSet<Rule>()
        for (a in assessments) {
            seen += a.rule
            val threshold = if (a.rule in active) a.exit else a.enter
            if (a.magnitude > threshold) active += a.rule else active -= a.rule
        }
        active.retainAll(seen)
        if (Rule.LEVEL_GROSS in active) active -= Rule.LEVEL

        val order = input.structure.order
        val activeCues = assessments.filter { it.rule in active }.sortedBy { order.indexOf(it.rule) }
        var arrows = Arrows(target = target, rollDeg = input.pose?.rollDeg?.takeIf { geometry.usesLevel })
        for (a in activeCues) arrows = a.arrows(arrows)

        val searching = geometry.tracksVehicle && subject == null
        val penalty = activeCues.sumOf { it.cue.penalty.toDouble() }.toFloat()
        val score = if (searching) 0 else (100f - penalty).roundToInt().coerceIn(0, 100)
        // A driving shot: nothing to track, nothing to level, the brief does the coaching.
        val rolling = !geometry.tracksVehicle && !geometry.usesLevel
        val shoot = !searching && subject != null && score >= SHOOT_SCORE &&
            activeCues.all { it.cue.penalty < SHOOT_MAX_PENALTY }

        val best = activeCues.firstOrNull()
        val previous = primaryRule
        val sameWord = best != null && best.cue.instruction == primaryInstruction
        if (previous != null && best?.rule != previous && previous !in active && !sameWord) {
            goodUntilMs = input.nowMs + GOOD_BEAT_MS
        }
        primaryRule = best?.rule
        primaryInstruction = best?.cue?.instruction
        if (shoot) {
            if (shootSinceMs == 0L) shootSinceMs = input.nowMs
        } else {
            shootSinceMs = 0L
        }

        val headline: String
        val why: String
        when {
            searching -> {
                headline = Instruction.FIND_CAR.word
                why = "Keep one whole car or motorcycle in view so the on-device detector can lock on."
            }
            best != null && input.nowMs < goodUntilMs -> {
                headline = "GOOD"
                why = "That one is fixed. Next: ${best.cue.text.lowercase()}."
            }
            best != null -> {
                headline = best.cue.text
                why = best.cue.why
            }
            shoot -> {
                val perfect = input.nowMs - shootSinceMs < PERFECT_BEAT_MS
                headline = if (perfect) Instruction.PERFECT.word else Instruction.SHOOT.word
                why = "Level, angle, size, aim and light are all inside tolerance."
            }
            rolling && subject == null -> {
                headline = Instruction.FRAME_IT.word
                why = "Burst, keep the car in the same spot on the screen, elbows braced."
            }
            subject == null -> {
                headline = Instruction.FRAME_IT.word
                why = "Drag a box around the detail for framing help. Keep it level."
            }
            else -> {
                headline = Instruction.PERFECT.word
                why = "Everything measurable is inside tolerance."
            }
        }

        return Guidance(
            primary = best?.cue,
            secondary = activeCues.drop(1).take(3).map { it.cue },
            headline = headline,
            why = why,
            score = score,
            shoot = shoot,
            arrows = arrows,
            searching = searching
        )
    }

    private fun assessLevel(pose: Pose, out: MutableList<Assessment>) {
        val roll = pose.rollDeg
        val magnitude = abs(roll)
        val side = if (roll > 0f) "Raise the right side" else "Raise the left side"
        val why = "$side ${magnitude.roundToInt()}°: a sloping ground line makes a parked car look like it is rolling away."
        val cueGross = Cue(Rule.LEVEL_GROSS, Instruction.LEVEL, Instruction.LEVEL.word, why, min(20f, magnitude * 4f))
        out += Assessment(Rule.LEVEL_GROSS, magnitude, LEVEL_GROSS_ENTER, LEVEL_GROSS_EXIT, cueGross)
        val cueFine = Cue(Rule.LEVEL, Instruction.LEVEL, Instruction.LEVEL.word, why, min(20f, magnitude * 4f))
        out += Assessment(Rule.LEVEL, magnitude, LEVEL_ENTER, LEVEL_EXIT, cueFine)
    }

    private fun assessPitch(pose: Pose, out: MutableList<Assessment>) {
        val pitch = pose.pitchDeg
        val cue = Cue(
            Rule.PITCH, Instruction.LOWER, Instruction.LOWER.word,
            "The phone points down ${max(0f, pitch).roundToInt()}°. Crouch to headlight height and aim level: eye level is a snapshot, headlight height is a magazine.",
            min(25f, max(0f, pitch) * 1.5f)
        )
        out += Assessment(Rule.PITCH, pitch, PITCH_ENTER, PITCH_EXIT, cue) { it.copy(lower = true) }
    }

    private fun assessCropped(box: NormalizedBox, out: MutableList<Assessment>) {
        val overflow = maxOf(
            EDGE_MARGIN_X - box.left, box.right - (1f - EDGE_MARGIN_X),
            EDGE_MARGIN_Y - box.top, box.bottom - (1f - EDGE_MARGIN_Y)
        )
        val cue = Cue(
            Rule.CROPPED, Instruction.BACK, Instruction.BACK.word,
            "The car touches the edge of the frame. A cut-off tyre always looks like a mistake; give it air on every side.",
            15f
        )
        out += Assessment(Rule.CROPPED, overflow, 0f, -EDGE_EXIT_MARGIN, cue) { it.copy(size = -1) }
    }

    private fun assessAngle(input: Input, subject: DetectedSubject, out: MutableList<Assessment>) {
        val angles = input.category.geometry.viewingAngles
        if (angles.isEmpty()) return
        val viewing = input.viewingAngle
        if (viewing != null) {
            val target = angles.minByOrNull { abs(PoseMath.wrap(it - viewing)) } ?: return
            val err = PoseMath.wrap(target - viewing)
            val tol = input.category.geometry.angleTolerance
            val magnitude = abs(err)
            val left = err > 0f
            val instruction = if (left) Instruction.WALK_LEFT else Instruction.WALK_RIGHT
            val cue = Cue(
                Rule.ANGLE, instruction, "${instruction.word} ${magnitude.roundToInt()}°",
                "You are at ${viewing.roundToInt()}°, this shot wants ${target.roundToInt()}°. Keep the camera on the car while you walk.",
                min(40f, magnitude * 0.6f)
            )
            out += Assessment(Rule.ANGLE, magnitude, tol, tol * ANGLE_EXIT_FRACTION, cue) { it.copy(orbitDeg = err) }
            return
        }
        // Not calibrated: judge the shape of the box. Wide box = side view, tall box = end view.
        val aspect = subject.imageAspect
        val want = (abs(angles[0]) % 180f).roundToInt()
        val (magnitude, why) = when (want) {
            90 -> (SIDE_MIN_ASPECT - aspect) to "Walk around until you see the full side, doors square to you. Tap NOSE when facing the nose to get degrees."
            0 -> (aspect - END_MAX_ASPECT) to "Walk around to face the car straight on. Tap NOSE when facing the nose to get degrees."
            else -> max(THREE_QUARTER_MIN_ASPECT - aspect, aspect - THREE_QUARTER_MAX_ASPECT) to
                "Walk around to a three-quarter view: face and flank at once. Tap NOSE when facing the nose to get degrees."
        }
        val cue = Cue(Rule.ANGLE, Instruction.WALK_AROUND, Instruction.WALK_AROUND.word, why, 20f)
        out += Assessment(Rule.ANGLE, magnitude, 0f, -ASPECT_EXIT_MARGIN, cue)
    }

    private fun assessSize(input: Input, subject: DetectedSubject, target: NormalizedBox, out: MutableList<Assessment>) {
        val motorcycle = subject.kind == VehicleKind.MOTORCYCLE && input.category.geometry.tracksVehicle
        val actual = if (motorcycle) subject.box.height else subject.box.width
        val wanted = if (motorcycle) target.height else target.width
        if (wanted <= 0.01f) return
        val err = (actual - wanted) / wanted
        val magnitude = abs(err)
        val penalty = min(25f, magnitude * 60f)
        val cue = if (err < 0f) {
            val recommended = input.category.recommendedZoom
            if (input.zoomRatio < recommended - 0.05f) {
                Cue(
                    Rule.SIZE, Instruction.ZOOM, "ZOOM ${formatZoom(recommended)}×",
                    "Back up and zoom rather than walking up close: distance keeps the proportions honest, the wide lens stretches the near end.",
                    penalty
                )
            } else {
                Cue(
                    Rule.SIZE, Instruction.CLOSER, Instruction.CLOSER.word,
                    "The car is too small; the background is taking over. Walk in until it fills the guide.",
                    penalty
                )
            }
        } else {
            Cue(
                Rule.SIZE, Instruction.BACK, Instruction.BACK.word,
                "The car fills too much of the frame. Back up for air around it; calmer proportions, safe margins.",
                penalty
            )
        }
        out += Assessment(Rule.SIZE, magnitude, SIZE_ENTER, SIZE_EXIT, cue) { it.copy(size = if (err < 0f) 1 else -1) }
    }

    private fun assessAim(box: NormalizedBox, target: NormalizedBox, out: MutableList<Assessment>) {
        val dx = target.centerX - box.centerX
        val aimLeft = dx > 0f
        val panCue = Cue(
            Rule.PAN, if (aimLeft) Instruction.AIM_LEFT else Instruction.AIM_RIGHT,
            if (aimLeft) Instruction.AIM_LEFT.word else Instruction.AIM_RIGHT.word,
            if (aimLeft) "Turn the phone a little left so the car slides right into the guide." else "Turn the phone a little right so the car slides left into the guide.",
            min(20f, abs(dx) * 100f)
        )
        out += Assessment(Rule.PAN, abs(dx), POS_ENTER, POS_EXIT, panCue) { it.copy(panX = if (aimLeft) -1 else 1) }

        val dy = target.centerY - box.centerY
        val aimUp = dy > 0f
        val tiltCue = Cue(
            Rule.TILT, if (aimUp) Instruction.AIM_UP else Instruction.AIM_DOWN,
            if (aimUp) Instruction.AIM_UP.word else Instruction.AIM_DOWN.word,
            if (aimUp) "Tilt the phone up a little, keep it at the same height." else "Tilt the phone down a little, keep it at the same height.",
            min(15f, abs(dy) * 80f)
        )
        out += Assessment(Rule.TILT, abs(dy), TILT_ENTER, TILT_EXIT, tiltCue) { it.copy(panY = if (aimUp) -1 else 1) }
    }

    private fun assessExposure(stats: FrameStats, hasSubject: Boolean, out: MutableList<Assessment>) {
        if (!hasSubject) return
        val bright = max(stats.highlightFraction - HIGHLIGHT_LIMIT, stats.meanLuma - BRIGHT_LIMIT)
        val dark = if (stats.meanLuma < DARK_LIMIT) stats.shadowFraction - SHADOW_LIMIT else -1f
        if (dark > bright) {
            val cue = Cue(
                Rule.EXPOSURE, Instruction.FIND_LIGHT, Instruction.FIND_LIGHT.word,
                "The body shape is disappearing into shadow. Turn the car toward open sky or move out of deep shade.",
                12f
            )
            out += Assessment(Rule.EXPOSURE, dark, 0f, -EXPOSURE_EXIT_MARGIN, cue)
        } else {
            val cue = Cue(
                Rule.EXPOSURE, Instruction.DARKER, Instruction.DARKER.word,
                "Bright paint or sky is losing detail. Tap the car and drag the sun slider down a little.",
                if (stats.meanLuma > BRIGHT_LIMIT) 12f else 8f
            )
            out += Assessment(Rule.EXPOSURE, bright, 0f, -EXPOSURE_EXIT_MARGIN, cue)
        }
    }

    private fun assessBackground(stats: FrameStats, out: MutableList<Assessment>) {
        val difference = abs(stats.leftEdgeDensity - stats.rightEdgeDensity)
        val busy = max(stats.leftEdgeDensity, stats.rightEdgeDensity)
        val magnitude = if (busy > CLUTTER_MIN_DENSITY) difference else -1f
        val leftCleaner = stats.leftEdgeDensity < stats.rightEdgeDensity
        val cue = Cue(
            Rule.BACKGROUND, if (leftCleaner) Instruction.STEP_LEFT else Instruction.STEP_RIGHT,
            if (leftCleaner) Instruction.STEP_LEFT.word else Instruction.STEP_RIGHT.word,
            "That side of the frame has fewer hard background edges competing with the car.",
            8f
        )
        out += Assessment(Rule.BACKGROUND, magnitude, CLUTTER_ENTER, CLUTTER_EXIT, cue)
    }

    private fun formatZoom(zoom: Float): String =
        if (zoom == zoom.toInt().toFloat()) zoom.toInt().toString() else zoom.toString()

    companion object {
        const val LEVEL_ENTER = 1.5f
        const val LEVEL_EXIT = 1.0f
        const val LEVEL_GROSS_ENTER = 8f
        const val LEVEL_GROSS_EXIT = 5f
        const val PITCH_ENTER = 6f
        const val PITCH_EXIT = 4f
        const val POS_ENTER = 0.05f
        const val POS_EXIT = 0.03f
        const val TILT_ENTER = 0.06f
        const val TILT_EXIT = 0.035f
        const val SIZE_ENTER = 0.12f
        const val SIZE_EXIT = 0.07f
        const val ANGLE_EXIT_FRACTION = 0.6f
        const val EDGE_MARGIN_X = 0.025f
        const val EDGE_MARGIN_Y = 0.045f
        const val EDGE_EXIT_MARGIN = 0.02f
        const val SIDE_MIN_ASPECT = 2.6f
        const val END_MAX_ASPECT = 1.8f
        const val THREE_QUARTER_MIN_ASPECT = 1.7f
        const val THREE_QUARTER_MAX_ASPECT = 3.0f
        const val ASPECT_EXIT_MARGIN = 0.15f
        const val HIGHLIGHT_LIMIT = 0.10f
        const val BRIGHT_LIMIT = 0.78f
        const val DARK_LIMIT = 0.18f
        const val SHADOW_LIMIT = 0.35f
        const val EXPOSURE_EXIT_MARGIN = 0.03f
        const val CLUTTER_MIN_DENSITY = 0.20f
        const val CLUTTER_ENTER = 0.035f
        const val CLUTTER_EXIT = 0.02f
        const val LEAD_SHIFT = 0.05f
        const val MOTORCYCLE_HEIGHT_FACTOR = 0.85f
        const val SHOOT_SCORE = 85
        const val SHOOT_MAX_PENALTY = 10f
        const val GOOD_BEAT_MS = 450L
        const val PERFECT_BEAT_MS = 700L
    }
}
