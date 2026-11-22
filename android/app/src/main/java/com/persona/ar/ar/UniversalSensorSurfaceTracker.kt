package com.persona.ar.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/**
 * 零 GMS 依赖的通用实景桌面视差追踪器 (Universal Sensor Surface Tracker)
 * 纯基于 Android 原生 IMU 姿态传感器与透视几何学，全机型 100% 离线可用
 */
class UniversalSensorSurfaceTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _spatialAnchor = MutableStateFlow(ARSpatialAnchor())
    val spatialAnchor: StateFlow<ARSpatialAnchor> = _spatialAnchor.asStateFlow()

    private val _trackingStatus = MutableStateFlow(ARTrackingStatus.PLANE_DETECTED)
    val trackingStatus: StateFlow<ARTrackingStatus> = _trackingStatus.asStateFlow()

    // 姿态滤波值
    private var pitch = 45f // 俯仰角 (0° 垂直立着, 90° 水平俯视桌面)
    private var roll = 0f   // 横滚角 (-90° ~ 90°)
    private val alpha = 0.15f // 低通滤波系数，消除手抖微噪

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        val sensorToUse = rotationVectorSensor ?: gravitySensor ?: accelerometer
        sensorToUse?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // 转换为角度
                val currentPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                val currentRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                applyFilter(currentPitch, currentRoll)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val currentPitch = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gz.toDouble())).toFloat()
                val currentRoll = Math.toDegrees(kotlin.math.atan2(-gx.toDouble(), kotlin.math.sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
                applyFilter(currentPitch, currentRoll)
            }
        }
    }

    private fun applyFilter(targetPitch: Float, targetRoll: Float) {
        pitch = pitch + alpha * (targetPitch - pitch)
        roll = roll + alpha * (targetRoll - roll)

        val currentAnchor = _spatialAnchor.value
        if (currentAnchor.isAnchored) {
            // 计算由于相机姿态变化引起的视差微位移 (Parallax Shift)
            val pitchRad = (pitch * PI / 180f).toFloat()
            val rollRad = (roll * PI / 180f).toFloat()

            // 视差位移：俯视程度越深，垂直视差越强
            val parallaxOffsetX = sin(rollRad) * 40f
            val parallaxOffsetY = cos(pitchRad) * 35f

            // 伴侣眼神朝向现实镜头微调
            val gazeX = sin(rollRad).coerceIn(-1f, 1f)
            val gazeY = (sin(pitchRad) - 0.65f).coerceIn(-1f, 1f)

            // 估算物理距离 (基于相机俯仰三角测距模型，假设手持高度约 35~45cm)
            val estimatedDistance = (0.35f / (sin(pitchRad.coerceIn(0.2f, 1.4f)))).coerceIn(0.4f, 1.8f)

            _spatialAnchor.value = currentAnchor.copy(
                distanceMeters = (estimatedDistance * 10f).toInt() / 10f,
                parallaxShiftX = parallaxOffsetX,
                parallaxShiftY = parallaxOffsetY,
                cameraGazeOffsetX = gazeX,
                cameraGazeOffsetY = gazeY
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 点击屏幕桌面进行无感锚定
     */
    fun anchorToDesktop(screenX: Float, screenY: Float) {
        _spatialAnchor.value = _spatialAnchor.value.copy(
            screenX = screenX,
            screenY = screenY,
            isAnchored = true,
            distanceMeters = 0.65f,
            planeTypeDescription = "书桌实景 (离线通用视差)"
        )
        _trackingStatus.value = ARTrackingStatus.ANCHORED
    }

    /**
     * 手势缩放与旋转
     */
    fun updateScale(scaleMultiplier: Float) {
        val current = _spatialAnchor.value.scaleFactor
        val next = (current * scaleMultiplier).coerceIn(0.4f, 2.2f)
        _spatialAnchor.value = _spatialAnchor.value.copy(scaleFactor = next)
    }

    fun updateRotation(yawDeltaDegree: Float) {
        val current = _spatialAnchor.value.rotationYaw
        val next = (current + yawDeltaDegree) % 360f
        _spatialAnchor.value = _spatialAnchor.value.copy(rotationYaw = next)
    }

    fun resetAnchor() {
        _spatialAnchor.value = ARSpatialAnchor(
            screenX = 0.5f,
            screenY = 0.65f,
            distanceMeters = 0.65f,
            scaleFactor = 1.0f,
            rotationYaw = 0f,
            isAnchored = false,
            planeTypeDescription = "未锚定"
        )
        _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
    }
}
