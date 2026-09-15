package be.roadframe.coach

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Pure geometry shared by the sensor layer and the coach. No Android types, so every formula has
 * a unit test.
 *
 * Gravity input follows the Android TYPE_GRAVITY convention: the vector points *up* in device
 * axes (upright portrait phone: y = +9.81; flat on a table, screen up: z = +9.81).
 */
object PoseMath {
    /** Wraps degrees into -180..180. */
    fun wrap(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Roll of the picture in degrees. 0 = level, positive = the phone is rotated clockwise as the
     * user sees it (right side down). [displayRotationDegrees] is the Surface rotation of the
     * display (0, 90, 180, 270) so landscape reads 0 when the horizon is level.
     */
    fun rollDegrees(gravityX: Float, gravityY: Float, displayRotationDegrees: Int): Float {
        val raw = Math.toDegrees(atan2(gravityX.toDouble(), gravityY.toDouble())).toFloat()
        return wrap(-raw + displayRotationDegrees)
    }

    /** Pitch of the camera axis in degrees. 0 = horizontal, positive = the camera points down. */
    fun pitchDegrees(gravityX: Float, gravityY: Float, gravityZ: Float): Float {
        val planar = hypot(gravityX, gravityY)
        return Math.toDegrees(atan2(gravityZ.toDouble(), planar.toDouble())).toFloat()
    }

    /**
     * Heading of the camera axis (device -Z) on the horizontal plane, 0..360 clockwise, from a
     * 3x3 row-major rotation matrix as produced by SensorManager.getRotationMatrixFromVector
     * (device to world, world X = east, Y = north, Z = up).
     */
    fun headingDegrees(rotationMatrix: FloatArray): Float {
        val east = -rotationMatrix[2]
        val north = -rotationMatrix[5]
        val heading = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
        return (heading + 360f) % 360f
    }

    /**
     * Field of view visible along one screen axis, in degrees, given the field of view of the
     * full sensor along that axis, the fraction of that axis the FILL_CENTER crop shows, and the
     * current zoom ratio.
     */
    fun visibleFovDegrees(sensorFovDegrees: Float, visibleFraction: Float, zoomRatio: Float): Float {
        val half = tan(Math.toRadians(sensorFovDegrees / 2.0)) * visibleFraction / zoomRatio.coerceAtLeast(0.1f)
        return Math.toDegrees(2.0 * atan(half)).toFloat()
    }

    /**
     * Field of view along the screen's horizontal and vertical axes for a 4:3 stream shown in a
     * FILL_CENTER view. [sensorLongFov] is the sensor's field of view along its long side.
     */
    fun viewFov(
        sensorLongFov: Float,
        sensorShortFov: Float,
        viewWidth: Int,
        viewHeight: Int,
        portrait: Boolean,
        zoomRatio: Float
    ): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0) return sensorLongFov to sensorShortFov
        val imageW = if (portrait) 3f else 4f
        val imageH = if (portrait) 4f else 3f
        val scale = maxOf(viewWidth / imageW, viewHeight / imageH)
        val visibleX = (viewWidth / (imageW * scale)).coerceIn(0.05f, 1f)
        val visibleY = (viewHeight / (imageH * scale)).coerceIn(0.05f, 1f)
        val fovX = if (portrait) sensorShortFov else sensorLongFov
        val fovY = if (portrait) sensorLongFov else sensorShortFov
        return visibleFovDegrees(fovX, visibleX, zoomRatio) to visibleFovDegrees(fovY, visibleY, zoomRatio)
    }

    /**
     * Where a box seen at [anchorPose] sits now that the phone is at [currentPose]. Turning the
     * camera right moves the scene left; pointing it further down moves the scene up. The shift is
     * tan(delta) times the focal length in screen widths, 1 / (2 tan(fov / 2)).
     */
    fun predictBox(
        anchor: NormalizedBox,
        anchorPose: Pose,
        currentPose: Pose,
        viewFovXDegrees: Float,
        viewFovYDegrees: Float
    ): NormalizedBox {
        val yaw = wrap(currentPose.headingDeg - anchorPose.headingDeg).coerceIn(-60f, 60f)
        val pitch = (currentPose.pitchDeg - anchorPose.pitchDeg).coerceIn(-60f, 60f)
        val focalX = 1.0 / (2.0 * tan(Math.toRadians(viewFovXDegrees.coerceIn(5f, 170f) / 2.0)))
        val focalY = 1.0 / (2.0 * tan(Math.toRadians(viewFovYDegrees.coerceIn(5f, 170f) / 2.0)))
        val dx = (-tan(Math.toRadians(yaw.toDouble())) * focalX).toFloat()
        val dy = (-tan(Math.toRadians(pitch.toDouble())) * focalY).toFloat()
        return anchor.shifted(dx, dy)
    }

    /** True when two poses differ by less than a degree on every axis. */
    fun nearlyEqual(a: Pose, b: Pose): Boolean =
        abs(a.rollDeg - b.rollDeg) < 1f && abs(a.pitchDeg - b.pitchDeg) < 1f && abs(wrap(a.headingDeg - b.headingDeg)) < 1f
}
