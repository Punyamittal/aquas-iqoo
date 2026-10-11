package com.aquascope.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Watches the gyroscope and gravity sensor while a scan plays, so a scan where the phone
 * turned or slid on the surface can be rejected, and the way the phone was held can be
 * compared with the scans taught at the same place.
 */
class MotionMonitor(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotations = mutableListOf<Double>()
    private val gravities = mutableListOf<DoubleArray>()

    fun start() {
        synchronized(this) {
            rotations.clear()
            gravities.clear()
        }
        gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop(): Steadiness.Summary? {
        sensors.unregisterListener(this)
        return synchronized(this) { Steadiness.summarize(rotations.toList(), gravities.toList()) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        synchronized(this) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> rotations += sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
                else -> gravities += doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

object Steadiness {

    /**
     * [maxRotationRadS] is the fastest turn seen (smoothed over a few readings so one spike from
     * the speaker doesn't count), [tiltChangeDeg] how far the phone tipped from the first to the
     * last part of the scan, and [gravity] the average direction of gravity in phone axes.
     */
    data class Summary(
        val maxRotationRadS: Double?,
        val tiltChangeDeg: Double?,
        val gravity: List<Double>?
    )

    const val MAX_ROTATION_RAD_S = 0.8
    const val MAX_TILT_CHANGE_DEG = 8.0
    const val MAX_POSE_DIFFERENCE_DEG = 25.0
    private const val SMOOTHING = 5

    fun summarize(rotations: List<Double>, gravities: List<DoubleArray>): Summary? {
        if (rotations.isEmpty() && gravities.isEmpty()) return null
        val maxRotation = if (rotations.size >= SMOOTHING) {
            rotations.windowed(SMOOTHING).maxOf { it.average() }
        } else {
            rotations.maxOrNull()
        }
        var tilt: Double? = null
        var mean: List<Double>? = null
        if (gravities.isNotEmpty()) {
            val part = (gravities.size / 4).coerceAtLeast(1)
            tilt = angleDeg(average(gravities.take(part)), average(gravities.takeLast(part)))
            mean = average(gravities).toList()
        }
        return Summary(maxRotation, tilt, mean)
    }

    fun moved(s: Summary?): Boolean =
        s != null && ((s.maxRotationRadS ?: 0.0) > MAX_ROTATION_RAD_S || (s.tiltChangeDeg ?: 0.0) > MAX_TILT_CHANGE_DEG)

    /** Degrees between how this scan was held and the usual pose of the taught scans, or null. */
    fun poseDifferenceDeg(gravity: List<Double>?, taught: List<List<Double>>): Double? {
        if (gravity == null || taught.size < 2) return null
        val typical = average(taught.map { it.toDoubleArray() })
        return angleDeg(gravity.toDoubleArray(), typical)
    }

    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = sqrt(a.sumOf { it * it })
        val nb = sqrt(b.sumOf { it * it })
        if (na < 1e-9 || nb < 1e-9) return 0.0
        val cos = (a.indices.sumOf { a[it] * b[it] } / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private fun average(rows: List<DoubleArray>): DoubleArray =
        DoubleArray(3) { i -> rows.sumOf { it[i] } / rows.size }
}
