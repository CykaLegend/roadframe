package be.roadframe.coach

import kotlin.math.max
import kotlin.math.min

enum class VehicleKind(val displayName: String) {
    CAR("Car"),
    MOTORCYCLE("Motorcycle");

    companion object {
        fun fromModelLabel(label: String): VehicleKind? = when (label.trim().lowercase()) {
            "car", "truck", "bus", "van" -> CAR
            "motorcycle", "motorbike" -> MOTORCYCLE
            else -> null
        }
    }
}

enum class SubjectPreference(val chipLabel: String) {
    AUTO("AUTO"),
    CAR("CAR"),
    MOTORCYCLE("MOTO");

    fun next(): SubjectPreference = entries[(ordinal + 1) % entries.size]

    fun accepts(kind: VehicleKind): Boolean = when (this) {
        AUTO -> true
        CAR -> kind == VehicleKind.CAR
        MOTORCYCLE -> kind == VehicleKind.MOTORCYCLE
    }
}

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val aspectRatio: Float get() = if (height > 0.0001f) width / height else 1f

    fun clamped(): NormalizedBox = NormalizedBox(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f)
    )

    fun shifted(dx: Float, dy: Float): NormalizedBox = NormalizedBox(left + dx, top + dy, right + dx, bottom + dy)

    fun lerp(other: NormalizedBox, amount: Float): NormalizedBox {
        val t = amount.coerceIn(0f, 1f)
        return NormalizedBox(
            left + (other.left - left) * t,
            top + (other.top - top) * t,
            right + (other.right - right) * t,
            bottom + (other.bottom - bottom) * t
        )
    }

    fun iou(other: NormalizedBox): Float {
        val x1 = max(left, other.left)
        val y1 = max(top, other.top)
        val x2 = min(right, other.right)
        val y2 = min(bottom, other.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = width * height + other.width * other.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        fun centered(centerX: Float, centerY: Float, width: Float, height: Float): NormalizedBox {
            val halfW = width / 2f
            val halfH = height / 2f
            return NormalizedBox(
                centerX - halfW,
                centerY - halfH,
                centerX + halfW,
                centerY + halfH
            ).clamped()
        }
    }
}

data class DetectedSubject(
    val box: NormalizedBox,
    val kind: VehicleKind,
    val label: String,
    val confidence: Float,
    /** Width over height of the detection in image pixels: the shape of the car, not of the screen. */
    val imageAspect: Float = box.aspectRatio
)

data class FrameStats(
    val meanLuma: Float = 0.5f,
    val highlightFraction: Float = 0f,
    val shadowFraction: Float = 0f,
    val leftEdgeDensity: Float = 0f,
    val rightEdgeDensity: Float = 0f
)

data class RawDetection(
    val box: android.graphics.RectF,
    val kind: VehicleKind,
    val label: String,
    val confidence: Float
)

data class AnalysisFrame(
    val detections: List<RawDetection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    /** Wall clock (elapsedRealtimeNanos) of the moment the analyzer received the frame. */
    val capturedAtNanos: Long,
    val inferenceMillis: Long,
    val stats: FrameStats
)

/** Orientation of the phone from the fused sensors. Angles in degrees. */
data class Pose(
    /** 0 = level. Positive = the phone is rotated clockwise as the user sees it (right side down). */
    val rollDeg: Float,
    /** 0 = camera horizontal. Positive = the camera points down. */
    val pitchDeg: Float,
    /** Direction the camera axis points on the horizontal plane, 0..360, clockwise. Relative, gyro-stable. */
    val headingDeg: Float,
    val timestampNanos: Long
)

/** One measurable thing the coach can complain about. */
enum class Rule {
    CROPPED, LEVEL_GROSS, ANGLE, SIZE, PAN, TILT, PITCH, LEVEL, EXPOSURE, BACKGROUND
}

/**
 * The order in which active rules are turned into the single spoken instruction. Selectable in
 * settings so different orderings can be compared on the same car.
 */
enum class CoachStructure(val label: String, val summary: String, val order: List<Rule>) {
    ROADFRAME(
        "RoadFrame",
        "Level first only when it is far off, then walk to the angle, then distance, then aim, then get " +
            "low, then fine level, light and background. Big moves before small ones: every later step " +
            "survives the earlier ones.",
        listOf(
            Rule.CROPPED, Rule.LEVEL_GROSS, Rule.ANGLE, Rule.SIZE, Rule.PAN, Rule.TILT, Rule.PITCH,
            Rule.LEVEL, Rule.EXPOSURE, Rule.BACKGROUND
        )
    ),
    HANDOFF(
        "Handoff",
        "The order from the build brief: frame position, distance, camera height, orientation, level, " +
            "exposure, background. Aims first, walks later.",
        listOf(
            Rule.CROPPED, Rule.PAN, Rule.TILT, Rule.SIZE, Rule.PITCH, Rule.ANGLE, Rule.LEVEL_GROSS,
            Rule.LEVEL, Rule.EXPOSURE, Rule.BACKGROUND
        )
    ),
    REFERENCE(
        "Prototype",
        "The order of the browser prototype: angle, height, size, aim, level, light. Walks first, " +
            "levels last.",
        listOf(
            Rule.CROPPED, Rule.ANGLE, Rule.PITCH, Rule.SIZE, Rule.PAN, Rule.TILT, Rule.LEVEL_GROSS,
            Rule.LEVEL, Rule.EXPOSURE, Rule.BACKGROUND
        )
    );

    fun next(): CoachStructure = entries[(ordinal + 1) % entries.size]
}

enum class Instruction(val word: String) {
    FIND_CAR("FIND THE CAR"),
    FRAME_IT("FRAME IT"),
    LEVEL("LEVEL"),
    WALK_LEFT("WALK LEFT"),
    WALK_RIGHT("WALK RIGHT"),
    WALK_AROUND("WALK AROUND"),
    BACK("BACK"),
    CLOSER("CLOSER"),
    ZOOM("ZOOM"),
    AIM_LEFT("AIM LEFT"),
    AIM_RIGHT("AIM RIGHT"),
    AIM_UP("AIM UP"),
    AIM_DOWN("AIM DOWN"),
    LOWER("LOWER"),
    DARKER("DARKER"),
    FIND_LIGHT("FIND LIGHT"),
    STEP_LEFT("STEP LEFT"),
    STEP_RIGHT("STEP RIGHT"),
    PERFECT("PERFECT"),
    SHOOT("SHOOT")
}

/** One active correction: the short word the coach says and the one-line reason behind it. */
data class Cue(
    val rule: Rule,
    val instruction: Instruction,
    val text: String,
    val why: String,
    val penalty: Float
)

/** What the overlay draws. Directions are in screen space: -1 left/up, +1 right/down. */
data class Arrows(
    val panX: Int = 0,
    val panY: Int = 0,
    /** +1 = get closer (arrows point in), -1 = back up (arrows point out). */
    val size: Int = 0,
    /** Degrees still to walk around the car; positive = walk left. */
    val orbitDeg: Float? = null,
    val lower: Boolean = false,
    val rollDeg: Float? = null,
    val target: NormalizedBox? = null
)

data class Guidance(
    val primary: Cue?,
    val secondary: List<Cue>,
    val headline: String,
    val why: String,
    val score: Int,
    /** Everything measurable is inside tolerance: ring turns green, haptic tick. */
    val shoot: Boolean,
    val arrows: Arrows,
    /** No subject to coach yet. */
    val searching: Boolean
)

internal fun Float.clamp01(): Float = max(0f, min(1f, this))
