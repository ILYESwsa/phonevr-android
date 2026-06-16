package com.ilyeswsa.phonevr.tracking

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Madgwick IMU sensor fusion filter.
 * Fuses accelerometer + gyroscope into a stable quaternion (rotation in 3D space).
 * This is what gives us head rotation — the core of 3DoF tracking.
 *
 * Output quaternion format: [w, x, y, z]
 */
class MadgwickFilter(private val beta: Float = 0.1f) : SensorEventListener {

    // Quaternion (orientation state)
    var q0 = 1f; var q1 = 0f; var q2 = 0f; var q3 = 0f

    private var lastTimestamp = 0L
    private var gyroX = 0f; var gyroY = 0f; var gyroZ = 0f
    private var accelX = 0f; var accelY = 0f; var accelZ = 0f

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]

                val now = event.timestamp
                if (lastTimestamp != 0L) {
                    val dt = (now - lastTimestamp) * 1e-9f
                    update(gyroX, gyroY, gyroZ, accelX, accelY, accelZ, dt)
                }
                lastTimestamp = now
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun update(gx: Float, gy: Float, gz: Float,
                       ax: Float, ay: Float, az: Float, dt: Float) {
        var norm: Float

        // Normalize accelerometer
        norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm == 0f) return
        val axN = ax / norm; val ayN = ay / norm; val azN = az / norm

        // Precompute repeated terms
        val _2q0 = 2f * q0; val _2q1 = 2f * q1
        val _2q2 = 2f * q2; val _2q3 = 2f * q3
        val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
        val _8q1 = 8f * q1; val _8q2 = 8f * q2
        val q0q0 = q0 * q0; val q1q1 = q1 * q1
        val q2q2 = q2 * q2; val q3q3 = q3 * q3

        // Gradient descent algorithm corrective step
        var s0 = _4q0 * q2q2 + _2q2 * axN + _4q0 * q1q1 - _2q1 * ayN
        var s1 = _4q1 * q3q3 - _2q3 * axN + 4f * q0q0 * q1 - _2q0 * ayN - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * azN
        var s2 = 4f * q0q0 * q2 + _2q0 * axN + _4q2 * q3q3 - _2q3 * ayN - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * azN
        var s3 = 4f * q1q1 * q3 - _2q1 * axN + 4f * q2q2 * q3 - _2q2 * ayN

        norm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
        s0 /= norm; s1 /= norm; s2 /= norm; s3 /= norm

        // Rate of change of quaternion from gyroscope
        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - beta * s0
        val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - beta * s1
        val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - beta * s2
        val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - beta * s3

        // Integrate to get quaternion
        q0 += qDot0 * dt; q1 += qDot1 * dt
        q2 += qDot2 * dt; q3 += qDot3 * dt

        // Normalize quaternion
        norm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= norm; q1 /= norm; q2 /= norm; q3 /= norm
    }

    /** Returns the current head pose as a float array [w, x, y, z] */
    fun getQuaternion(): FloatArray = floatArrayOf(q0, q1, q2, q3)

    /** Reset to identity rotation */
    fun reset() { q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f; lastTimestamp = 0L }

    fun register(sensorManager: SensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun unregister(sensorManager: SensorManager) {
        sensorManager.unregisterListener(this)
    }
}
