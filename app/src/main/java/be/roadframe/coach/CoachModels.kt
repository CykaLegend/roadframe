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

enum class ShotMode(val chipLabel: String, val description: String) {
    BALANCED("BALANCED", "Natural proportions and calm framing"),
    SALES("SALE", "Clear, centered and honest vehicle coverage"),
    CINEMATIC("CINEMATIC", "Intentional negative space for atmosphere"),
    DETAIL("DETAIL", "Close-up restoration and detailing work");

    fun next(): ShotMode = entries[(ordinal + 1) % entries.size]
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

    fun lerp(other: NormalizedBox, amount: Float): NormalizedBox {
        val t = amount.coerceIn(0f, 1f)
        return NormalizedBox(
            left + (other.left - left) * t,
            top + (other.top - top) * t,
            right + (other.right - right) * t,
            bottom + (other.bottom - bottom) * t
        )
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
    val confidence: Float
)

data class FrameStats(
    val meanLuma: Float = 0.5f,
    val highlightFraction: Float = 0f,
    val shadowFraction: Float = 0f,
    val leftEdgeDensity: Float = 0f,
    val rightEdgeDensity: Float = 0f
)

enum class AdviceAction {
    FIND_SUBJECT,
    SELECT_DETAIL,
    ROTATE_LEFT,
    ROTATE_RIGHT,
    STEP_BACK,
    STEP_CLOSER,
    SUBJECT_LEFT,
    SUBJECT_RIGHT,
    SUBJECT_UP,
    SUBJECT_DOWN,
    MOVE_LEFT,
    MOVE_RIGHT,
    LOWER_EXPOSURE,
    FIND_LIGHT,
    HOLD
}

data class CoachAdvice(
    val action: AdviceAction,
    val title: String,
    val explanation: String,
    val score: Int,
    val ready: Boolean,
    val targetBox: NormalizedBox?,
    val arrowX: Float = 0f,
    val arrowY: Float = 0f
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
    val inferenceMillis: Long,
    val stats: FrameStats
)

internal fun Float.clamp01(): Float = max(0f, min(1f, this))
