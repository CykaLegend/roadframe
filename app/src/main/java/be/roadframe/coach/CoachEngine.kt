package be.roadframe.coach

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure, deterministic coaching rules. The neural model locates the subject; this class turns
 * measurements into one explainable correction. Keeping this class Android-free makes every
 * recommendation unit-testable instead of hiding aesthetic guesses inside a black box.
 */
class CoachEngine {

    fun evaluate(
        subject: DetectedSubject?,
        mode: ShotMode,
        rollDegrees: Float,
        stats: FrameStats,
        detailSelectionPending: Boolean = false
    ): CoachAdvice {
        if (mode == ShotMode.DETAIL && detailSelectionPending) {
            return CoachAdvice(
                AdviceAction.SELECT_DETAIL,
                "Drag around the detail",
                "Select the badge, wheel, repair or cleaned area you want to compose.",
                0,
                false,
                null
            )
        }

        if (subject == null) {
            return CoachAdvice(
                AdviceAction.FIND_SUBJECT,
                "Find the vehicle",
                "Keep one complete car or motorcycle visible so the on-device detector can lock on.",
                0,
                false,
                null
            )
        }

        val target = targetFor(subject, mode)
        val score = score(subject.box, target, rollDegrees, stats)
        val box = subject.box

        if (abs(rollDegrees) > 2.5f) {
            val rotateLeft = rollDegrees > 0f
            return CoachAdvice(
                if (rotateLeft) AdviceAction.ROTATE_LEFT else AdviceAction.ROTATE_RIGHT,
                if (rotateLeft) "Rotate phone left" else "Rotate phone right",
                "The phone is ${abs(rollDegrees).toInt()}° off level; straight bodywork and buildings will lean.",
                score,
                false,
                target,
                if (rotateLeft) -0.7f else 0.7f,
                0f
            )
        }

        if (box.left < 0.025f || box.right > 0.975f || box.top < 0.045f || box.bottom > 0.955f) {
            return CoachAdvice(
                AdviceAction.STEP_BACK,
                "Step back",
                "The subject is touching the frame. Leave clean space around every edge before refining the angle.",
                score,
                false,
                target,
                0f,
                0.75f
            )
        }

        val actualSize = primarySize(box, subject.kind, mode)
        val targetSize = primarySize(target, subject.kind, mode)
        if (actualSize > targetSize + 0.09f) {
            return CoachAdvice(
                AdviceAction.STEP_BACK,
                "Step back",
                "The vehicle fills too much of the frame; extra distance gives calmer proportions and safe margins.",
                score,
                false,
                target,
                0f,
                0.8f
            )
        }
        if (actualSize < targetSize - 0.11f) {
            return CoachAdvice(
                AdviceAction.STEP_CLOSER,
                "Step closer",
                "The background is dominating. Make the vehicle the unmistakable subject without digital cropping.",
                score,
                false,
                target,
                0f,
                -0.8f
            )
        }

        val horizontalError = target.centerX - box.centerX
        if (abs(horizontalError) > 0.055f) {
            val subjectMovesRight = horizontalError > 0f
            return CoachAdvice(
                if (subjectMovesRight) AdviceAction.SUBJECT_RIGHT else AdviceAction.SUBJECT_LEFT,
                if (subjectMovesRight) "Move vehicle right →" else "← Move vehicle left",
                if (subjectMovesRight) {
                    "Pan the phone slightly left until the vehicle sits inside the guide."
                } else {
                    "Pan the phone slightly right until the vehicle sits inside the guide."
                },
                score,
                false,
                target,
                if (subjectMovesRight) 1f else -1f,
                0f
            )
        }

        val verticalError = target.centerY - box.centerY
        if (abs(verticalError) > 0.065f) {
            val subjectMovesDown = verticalError > 0f
            return CoachAdvice(
                if (subjectMovesDown) AdviceAction.SUBJECT_DOWN else AdviceAction.SUBJECT_UP,
                if (subjectMovesDown) "Move vehicle down ↓" else "Move vehicle up ↑",
                if (subjectMovesDown) {
                    "Tilt the phone slightly upward; keep the device itself at the same height."
                } else {
                    "Tilt the phone slightly downward; keep the device itself at the same height."
                },
                score,
                false,
                target,
                0f,
                if (subjectMovesDown) 1f else -1f
            )
        }

        if (stats.highlightFraction > 0.10f || stats.meanLuma > 0.78f) {
            return CoachAdvice(
                AdviceAction.LOWER_EXPOSURE,
                "Lower exposure",
                "Bright paint or sky is losing detail. Tap the body and pull the exposure control downward.",
                score,
                false,
                target
            )
        }
        if (stats.meanLuma < 0.18f && stats.shadowFraction > 0.35f) {
            return CoachAdvice(
                AdviceAction.FIND_LIGHT,
                "Find softer light",
                "The body shape is disappearing into shadow. Turn toward open sky or move out of deep shade.",
                score,
                false,
                target
            )
        }

        val cleanerSideDifference = abs(stats.leftEdgeDensity - stats.rightEdgeDensity)
        if (mode != ShotMode.DETAIL && cleanerSideDifference > 0.035f &&
            max(stats.leftEdgeDensity, stats.rightEdgeDensity) > 0.20f
        ) {
            val leftCleaner = stats.leftEdgeDensity < stats.rightEdgeDensity
            return CoachAdvice(
                if (leftCleaner) AdviceAction.MOVE_LEFT else AdviceAction.MOVE_RIGHT,
                if (leftCleaner) "Try one step left" else "Try one step right",
                "That side of the frame has fewer hard background edges competing with the vehicle.",
                score,
                false,
                target,
                if (leftCleaner) -1f else 1f,
                0f
            )
        }

        return CoachAdvice(
            AdviceAction.HOLD,
            "Hold it — frame is balanced",
            when (mode) {
                ShotMode.BALANCED -> "Size, spacing, level and exposure are inside the balanced target."
                ShotMode.SALES -> "The entire vehicle is clear, centered and safely inside the frame."
                ShotMode.CINEMATIC -> "The vehicle is anchored with deliberate negative space to its right."
                ShotMode.DETAIL -> "The selected detail has clean margins and enough visual weight."
            },
            max(score, 90),
            true,
            target
        )
    }

    fun targetFor(subject: DetectedSubject, mode: ShotMode): NormalizedBox {
        val targetCenterX = when (mode) {
            ShotMode.CINEMATIC -> 0.39f
            else -> 0.50f
        }
        val targetCenterY = when (mode) {
            ShotMode.DETAIL -> 0.50f
            ShotMode.CINEMATIC -> 0.59f
            else -> 0.57f
        }

        val targetPrimarySize = when (mode) {
            ShotMode.BALANCED -> if (subject.kind == VehicleKind.CAR) 0.73f else 0.66f
            ShotMode.SALES -> if (subject.kind == VehicleKind.CAR) 0.69f else 0.63f
            ShotMode.CINEMATIC -> if (subject.kind == VehicleKind.CAR) 0.60f else 0.58f
            ShotMode.DETAIL -> 0.58f
        }

        val aspect = subject.box.aspectRatio.coerceIn(0.45f, 3.4f)
        val targetWidth: Float
        val targetHeight: Float
        if (subject.kind == VehicleKind.MOTORCYCLE && mode != ShotMode.DETAIL) {
            targetHeight = targetPrimarySize
            targetWidth = (targetHeight * aspect).coerceAtMost(0.82f)
        } else {
            targetWidth = targetPrimarySize
            targetHeight = (targetWidth / aspect).coerceAtMost(0.72f)
        }
        return NormalizedBox.centered(targetCenterX, targetCenterY, targetWidth, targetHeight)
    }

    private fun primarySize(box: NormalizedBox, kind: VehicleKind, mode: ShotMode): Float {
        return if (kind == VehicleKind.MOTORCYCLE && mode != ShotMode.DETAIL) box.height else box.width
    }

    private fun score(
        box: NormalizedBox,
        target: NormalizedBox,
        rollDegrees: Float,
        stats: FrameStats
    ): Int {
        val centerPenalty = ((abs(box.centerX - target.centerX) +
            abs(box.centerY - target.centerY)) * 85f).coerceAtMost(30f)
        val sizePenalty = (abs(box.width - target.width) * 55f).coerceAtMost(25f)
        val rollPenalty = (abs(rollDegrees) * 4f).coerceAtMost(20f)
        val exposurePenalty = when {
            stats.meanLuma < 0.18f -> 12f
            stats.meanLuma > 0.78f -> 12f
            stats.highlightFraction > 0.10f -> 8f
            else -> 0f
        }
        val edgePenalty = if (
            box.left < 0.025f || box.right > 0.975f ||
            box.top < 0.045f || box.bottom > 0.955f
        ) 15f else 0f
        return (100f - centerPenalty - sizePenalty - rollPenalty - exposurePenalty - edgePenalty)
            .toInt().coerceIn(0, 100)
    }
}
