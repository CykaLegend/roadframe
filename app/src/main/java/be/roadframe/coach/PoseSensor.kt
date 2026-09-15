package be.roadframe.coach

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * The fast clock. Gravity gives roll and pitch, the game rotation vector (gyro fused with the
 * accelerometer, no magnetometer, so a big steel car nearby cannot bend it) gives a stable
 * relative heading of the camera axis. Both are requested at up to 200 Hz and every event
 * produces a new [Pose]. A one-second history lets the tracker look up the pose at the moment a
 * camera frame was captured.
 */
class PoseSensor(
    context: Context,
    private val displayRotationDegrees: () -> Int,
    private val onPose: (Pose) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val headingAvailable: Boolean = rotationSensor != null
    val available: Boolean = gravitySensor != null || accelerometer != null

    @Volatile
    var latest: Pose? = null
        private set

    private var gravityX = 0f
    private var gravityY = 9.81f
    private var gravityZ = 0f
    private var haveGravity = false
    private var heading = 0f
    private val rotationMatrix = FloatArray(9)
    private val history = ArrayDeque<Pose>()
    private val historyLock = Any()
    private var thread: HandlerThread? = null

    /** Events are delivered on their own thread so 400 callbacks a second never touch the UI thread. */
    fun start() {
        if (thread != null) return
        val worker = HandlerThread("roadframe-pose").also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)
        val gravity = gravitySensor ?: accelerometer
        gravity?.let { manager.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
        rotationSensor?.let { manager.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
    }

    fun stop() {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    /** The pose closest in time to [timestampNanos] within the last second, or the latest. */
    fun poseAt(timestampNanos: Long): Pose? = synchronized(historyLock) {
        history.minByOrNull { kotlin.math.abs(it.timestampNanos - timestampNanos) } ?: latest
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravityX = event.values[0]
                gravityY = event.values[1]
                gravityZ = event.values[2]
                haveGravity = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Raw accelerometer: a light low-pass so a footstep does not read as roll.
                gravityX += (event.values[0] - gravityX) * ACCELEROMETER_ALPHA
                gravityY += (event.values[1] - gravityY) * ACCELEROMETER_ALPHA
                gravityZ += (event.values[2] - gravityZ) * ACCELEROMETER_ALPHA
                haveGravity = true
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                heading = PoseMath.headingDegrees(rotationMatrix)
            }
            else -> return
        }
        if (!haveGravity) return
        val planar = kotlin.math.hypot(gravityX, gravityY)
        val roll = if (planar < MIN_PLANAR_GRAVITY) latest?.rollDeg ?: 0f
        else PoseMath.rollDegrees(gravityX, gravityY, displayRotationDegrees())
        val pose = Pose(
            rollDeg = roll,
            pitchDeg = PoseMath.pitchDegrees(gravityX, gravityY, gravityZ),
            headingDeg = heading,
            timestampNanos = event.timestamp
        )
        latest = pose
        synchronized(historyLock) {
            history.addLast(pose)
            val horizon = pose.timestampNanos - HISTORY_NANOS
            while (history.size > 1 && history.first().timestampNanos < horizon) history.removeFirst()
        }
        onPose(pose)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** 5 ms = 200 Hz; the framework clamps to what the hardware offers. */
        private const val SAMPLING_PERIOD_US = 5_000
        private const val ACCELEROMETER_ALPHA = 0.25f
        /** Below this the phone is flat and roll is meaningless. */
        private const val MIN_PLANAR_GRAVITY = 2.5f
        private const val HISTORY_NANOS = 1_000_000_000L
    }
}
