package be.roadframe.coach

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

class LevelSensor(
    context: Context,
    private val onRollChanged: (Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var filteredX = 0f
    private var filteredY = 9.81f

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = 0.18f
        filteredX += (event.values[0] - filteredX) * alpha
        filteredY += (event.values[1] - filteredY) * alpha
        val planarGravity = sqrt(filteredX * filteredX + filteredY * filteredY)
        if (planarGravity < 2.5f) return
        val roll = Math.toDegrees(atan2(filteredX.toDouble(), filteredY.toDouble())).toFloat()
        onRollChanged(roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
