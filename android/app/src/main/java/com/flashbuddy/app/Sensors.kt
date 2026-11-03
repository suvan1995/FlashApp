package com.flashbuddy.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Watches the system flashlight. Works no matter who turned it on
 * (quick settings, another app, or us), with no permission needed.
 */
class TorchWatcher(context: Context, private val onChange: (Boolean) -> Unit) {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val states = HashMap<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    var isOn = false
        private set

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            states[cameraId] = enabled
            val now = states.values.any { it }
            if (now != isOn) {
                isOn = now
                onChange(now)
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            // Camera is busy (e.g. camera app open). Keep the last known state.
        }
    }

    fun start() = cm.registerTorchCallback(callback, handler)
    fun stop() = cm.unregisterTorchCallback(callback)

    /** Switch the torch ourselves (used by the in-app button). */
    fun setTorch(on: Boolean): Boolean {
        val id = flashCameraId() ?: return false
        return try { cm.setTorchMode(id, on); true } catch (e: Exception) { false }
    }

    private fun flashCameraId(): String? = try {
        cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) { null }
}

/**
 * Tracks where the back of the phone (the flashlight) is pointing.
 * yaw: left/right turn relative to [recenter], radians (+ = turned right).
 * pitch: up/down, radians (+ = pointing up, 0 = level).
 */
class PointingTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var azimuth = 0f
    private var baseAz: Float? = null

    @Volatile var yaw = 0f
        private set
    @Volatile var pitch = 0f
        private set
    val available get() = sensor != null

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    fun recenter() { baseAz = null }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // Device -Z axis (out of the back, where the torch is) in world coords.
        val vx = -r[2]; val vy = -r[5]; val vz = -r[8]
        azimuth = atan2(vx, vy)
        pitch = asin(vz.coerceIn(-1f, 1f))
        val base = baseAz ?: azimuth.also { baseAz = it }
        val d = azimuth - base
        yaw = atan2(sin(d), cos(d))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
