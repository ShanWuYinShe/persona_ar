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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 零 GMS 依赖的通用实景 3D 物理空间锚定与姿态追踪器 (Universal Sensor Surface Tracker)
 * 1. 基于原生 IMU 旋转矢量传感器 (Game Rotation Vector / Rotation Vector) 获取全姿态 3D 旋转矩阵 (Yaw/Pitch/Roll)；
 * 2. 锚定时在现实物理世界固定 3D 空间坐标，实现真正的 AR 空间定点：
 *    - 移动/转动镜头时手办留在原物理方位；
 *    - 移开镜头手办平滑出画消失；
 *    - 移回镜头手办稳定在物理桌面原位重现；
 * 3. 动态计算视锥体内外状态与方位角差，为出画寻回雷达提供毫秒级角度指引。
 */
class UniversalSensorSurfaceTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // 优先无磁偏角漂移的 Game Rotation Vector，回退标准 Rotation Vector，再回退重力传感器
    private val gameRotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
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
    private var yaw = 0f    // 航向角 (-180° ~ 180°)
    private val alpha = 0.20f // 低通滤波系数

    // 传感器原始与滤波后的 3x3 旋转矩阵 (设备坐标系 -> 物理世界坐标系)
    private val rawRotationMatrix = FloatArray(9)
    private val currentRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private val orientationAngles = FloatArray(3)

    // 锚定时记录的基准姿态与物理世界 3D 坐标
    private val anchorRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private var worldAnchorX = 0f
    private var worldAnchorY = -0.12f
    private var worldAnchorZ = -0.65f
    private var hasAnchorPose = false

    // 相机光学参数 (垂直 FOV 约 60°，典型竖屏纵横比 9:19)
    private val verticalFovDegrees = 60f
    private val defaultAspectRatio = 1080f / 2232f

    fun start() {
        val sensorToUse = gameRotationVectorSensor ?: rotationVectorSensor ?: gravitySensor ?: accelerometer
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
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)
                SensorManager.getOrientation(rawRotationMatrix, orientationAngles)
                val currentYaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val currentPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                val currentRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                applyOrientationFilter(rawRotationMatrix, currentYaw, currentPitch, currentRoll)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val currentPitch = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gz.toDouble())).toFloat()
                val currentRoll = Math.toDegrees(kotlin.math.atan2(-gx.toDouble(), kotlin.math.sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
                // 重力传感器无法获得绝对 yaw，保持当前矩阵
                applyOrientationFilter(currentRotationMatrix, yaw, currentPitch, currentRoll)
            }
        }
    }

    private fun applyOrientationFilter(rawRot: FloatArray, targetYaw: Float, targetPitch: Float, targetRoll: Float) {
        pitch += alpha * (targetPitch - pitch)
        roll += alpha * (targetRoll - roll)
        yaw += alpha * (targetYaw - yaw)

        // 矩阵低通平滑
        for (i in 0 until 9) {
            currentRotationMatrix[i] += alpha * (rawRot[i] - currentRotationMatrix[i])
        }

        val currentAnchor = _spatialAnchor.value
        if (currentAnchor.isAnchored && hasAnchorPose) {
            // 计算相对旋转 M_rel = R_anchor^T * R_current
            // 将当前相机朝向与基向量投影到锚点所在的世界坐标系
            val r00 = anchorRotationMatrix[0]; val r01 = anchorRotationMatrix[1]; val r02 = anchorRotationMatrix[2]
            val r10 = anchorRotationMatrix[3]; val r11 = anchorRotationMatrix[4]; val r12 = anchorRotationMatrix[5]
            val r20 = anchorRotationMatrix[6]; val r21 = anchorRotationMatrix[7]; val r22 = anchorRotationMatrix[8]

            val c00 = currentRotationMatrix[0]; val c01 = currentRotationMatrix[1]; val c02 = currentRotationMatrix[2]
            val c10 = currentRotationMatrix[3]; val c11 = currentRotationMatrix[4]; val c12 = currentRotationMatrix[5]
            val c20 = currentRotationMatrix[6]; val c21 = currentRotationMatrix[7]; val c22 = currentRotationMatrix[8]

            // M_rel = R_anchor^T * R_curr
            // 相机在自身设备系中：forward = (0, 0, -1), up = (0, 1, 0), right = (1, 0, 0)
            // 故在锚点世界系中：
            // Forward = - (第 2 列)
            val fx = -(r00 * c02 + r10 * c12 + r20 * c22)
            val fy = -(r01 * c02 + r11 * c12 + r21 * c22)
            val fz = -(r02 * c02 + r12 * c12 + r22 * c22)

            // Up = (第 1 列)
            val ux = r00 * c01 + r10 * c11 + r20 * c21
            val uy = r01 * c01 + r11 * c11 + r21 * c21
            val uz = r02 * c01 + r12 * c11 + r22 * c21

            // Right = (第 0 列)
            val rx = r00 * c00 + r10 * c10 + r20 * c20
            val ry = r01 * c00 + r11 * c10 + r21 * c20
            val rz = r02 * c00 + r12 * c10 + r22 * c20

            // 锚点相对于当前相机的相对坐标 (Camera View Space)
            // P_cam = [ P · Right, P · Up, - P · Forward ] (因为相机看向 +Forward，在 OpenGL/Filament 对应 -Z)
            val px = worldAnchorX
            val py = worldAnchorY
            val pz = worldAnchorZ

            val viewX = px * rx + py * ry + pz * rz
            val viewY = px * ux + py * uy + pz * uz
            val viewZ = -(px * fx + py * fy + pz * fz) // viewZ > 0 表示在相机前方

            val tanHalfFov = tan(Math.toRadians(verticalFovDegrees.toDouble()) / 2.0).toFloat()
            val safeViewZ = if (viewZ > 0.05f) viewZ else 0.05f

            val ndcX = viewX / (safeViewZ * tanHalfFov * defaultAspectRatio)
            val ndcY = viewY / (safeViewZ * tanHalfFov)

            val projScreenX = (ndcX + 1f) / 2f
            val projScreenY = (1f - ndcY) / 2f

            val isInView = (viewZ > 0.08f) && (projScreenX in -0.12f..1.12f) && (projScreenY in -0.15f..1.15f)
            val azimuthDelta = Math.toDegrees(atan2(viewX.toDouble(), viewZ.toDouble())).toFloat()

            // 伴侣眼神朝向镜头
            val gazeX = (-viewX / safeViewZ).coerceIn(-1f, 1f)
            val gazeY = (-viewY / safeViewZ).coerceIn(-1f, 1f)

            _spatialAnchor.value = currentAnchor.copy(
                screenX = projScreenX,
                screenY = projScreenY,
                isWorldAnchored = true,
                worldAnchorX = worldAnchorX,
                worldAnchorY = worldAnchorY,
                worldAnchorZ = worldAnchorZ,
                cameraForwardX = fx,
                cameraForwardY = fy,
                cameraForwardZ = fz,
                cameraUpX = ux,
                cameraUpY = uy,
                cameraUpZ = uz,
                isInView = isInView,
                azimuthDeltaDegrees = azimuthDelta,
                cameraGazeOffsetX = gazeX,
                cameraGazeOffsetY = gazeY,
                cameraPitchDegrees = pitch,
                cameraRollDegrees = roll,
                cameraFocalDistance = 10f
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 点击屏幕桌面进行真实物理 3D 空间锚定
     */
    fun anchorToDesktop(screenX: Float, screenY: Float) {
        // 保存锚定瞬间的相机姿态作为世界基准坐标系
        System.arraycopy(currentRotationMatrix, 0, anchorRotationMatrix, 0, 9)
        hasAnchorPose = true

        val distance = 0.65f
        val tanHalfFov = tan(Math.toRadians(verticalFovDegrees.toDouble()) / 2.0).toFloat()
        val ndcX = 2f * screenX - 1f
        val ndcY = 1f - 2f * screenY

        // 锚点在初始相机坐标系下的 3D 坐标
        worldAnchorX = ndcX * tanHalfFov * defaultAspectRatio * distance
        worldAnchorY = ndcY * tanHalfFov * distance
        worldAnchorZ = -distance

        _spatialAnchor.value = _spatialAnchor.value.copy(
            screenX = screenX,
            screenY = screenY,
            isAnchored = true,
            isWorldAnchored = true,
            worldAnchorX = worldAnchorX,
            worldAnchorY = worldAnchorY,
            worldAnchorZ = worldAnchorZ,
            cameraForwardX = 0f,
            cameraForwardY = 0f,
            cameraForwardZ = -1f,
            cameraUpX = 0f,
            cameraUpY = 1f,
            cameraUpZ = 0f,
            isInView = true,
            azimuthDeltaDegrees = 0f,
            distanceMeters = distance,
            cameraPitchDegrees = pitch,
            cameraRollDegrees = roll,
            cameraFocalDistance = 10f,
            planeTypeDescription = "书桌实景 (真实 3D 空间锁定)"
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
        hasAnchorPose = false
        _spatialAnchor.value = ARSpatialAnchor(
            screenX = 0.5f,
            screenY = 0.65f,
            distanceMeters = 0.65f,
            scaleFactor = 1.0f,
            rotationYaw = 0f,
            isAnchored = false,
            isWorldAnchored = false,
            isInView = true,
            azimuthDeltaDegrees = 0f,
            planeTypeDescription = "未锚定"
        )
        _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
    }
}

