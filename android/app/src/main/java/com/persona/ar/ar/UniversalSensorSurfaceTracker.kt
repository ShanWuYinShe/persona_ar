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
    private var pitch = 45f // 俯仰角 (0° 平视，90° 水平俯视桌面)
    private var roll = 0f   // 横滚角 (-90° ~ 90°)
    private var yaw = 0f    // 航向角 (-180° ~ 180°)
    private val alpha = 0.22f // 低通滤波系数

    // 传感器原始与滤波后的 3x3 旋转矩阵 (设备坐标系 -> 物理世界坐标系)
    private val rawRotationMatrix = FloatArray(9)
    private val currentRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private val orientationAngles = FloatArray(3)

    // 锚定时记录的重力校平世界基矩阵（+Y 恒为地球重力正上方，消除斜拿手机导致手办歪斜）
    private val anchorRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private var worldAnchorX = 0f
    private var worldAnchorY = -0.12f
    private var worldAnchorZ = -0.65f
    private var hasAnchorPose = false
    private var hasLiveSensorData = false

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
                hasLiveSensorData = true
                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)
                SensorManager.getOrientation(rawRotationMatrix, orientationAngles)
                val currentYaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                // Android getOrientation()[1] 在手机向下俯视桌面时为负值（约 -90°..0°），转换为 0°(平视)..90°(垂直俯视)
                val rawDownPitch = (-Math.toDegrees(orientationAngles[1].toDouble()).toFloat()).coerceIn(0f, 90f)
                val currentRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                applyOrientationFilter(rawRotationMatrix, currentYaw, rawDownPitch, currentRoll)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val downPitch = Math.toDegrees(atan2(gz.toDouble(), gy.toDouble())).toFloat().coerceIn(0f, 90f)
                val currentRoll = Math.toDegrees(atan2(-gx.toDouble(), kotlin.math.sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
                applyOrientationFilter(currentRotationMatrix, yaw, downPitch, currentRoll)
            }
        }
    }

    internal fun applyOrientationFilter(rawRot: FloatArray, targetYaw: Float, targetPitch: Float, targetRoll: Float) {
        // 微小抖动死区：静止放在桌面时不产生细碎漂移
        if (kotlin.math.abs(targetPitch - pitch) > 0.25f) {
            pitch += alpha * (targetPitch - pitch)
        }
        if (kotlin.math.abs(targetRoll - roll) > 0.25f) {
            roll += alpha * (targetRoll - roll)
        }
        yaw += alpha * shortestAngleDelta(yaw, targetYaw)

        // 矩阵低通平滑 + Gram-Schmidt 正交化，防止非正交矩阵导致视差缩放畸变或长期漂移
        for (i in 0 until 9) {
            currentRotationMatrix[i] += alpha * (rawRot[i] - currentRotationMatrix[i])
        }
        orthonormalizeMatrix(currentRotationMatrix)

        val currentAnchor = _spatialAnchor.value
        if (currentAnchor.isAnchored && hasAnchorPose) {
            // 计算相对旋转 M_rel = R_anchor^T * R_current
            // anchorRotationMatrix 为重力校平的世界基准 (+Y = 地球重力正上方)，手办永不随仰俯斜躺
            val r00 = anchorRotationMatrix[0]; val r01 = anchorRotationMatrix[1]; val r02 = anchorRotationMatrix[2]
            val r10 = anchorRotationMatrix[3]; val r11 = anchorRotationMatrix[4]; val r12 = anchorRotationMatrix[5]
            val r20 = anchorRotationMatrix[6]; val r21 = anchorRotationMatrix[7]; val r22 = anchorRotationMatrix[8]

            val c00 = currentRotationMatrix[0]; val c01 = currentRotationMatrix[1]; val c02 = currentRotationMatrix[2]
            val c10 = currentRotationMatrix[3]; val c11 = currentRotationMatrix[4]; val c12 = currentRotationMatrix[5]
            val c20 = currentRotationMatrix[6]; val c21 = currentRotationMatrix[7]; val c22 = currentRotationMatrix[8]

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

            val px = worldAnchorX
            val py = worldAnchorY
            val pz = worldAnchorZ

            val viewX = px * rx + py * ry + pz * rz
            val viewY = px * ux + py * uy + pz * uz
            val viewZ = px * fx + py * fy + pz * fz

            val tanHalfFov = tan(Math.toRadians(verticalFovDegrees.toDouble()) / 2.0).toFloat()
            val safeViewZ = if (viewZ > 0.05f) viewZ else 0.05f

            val ndcX = viewX / (safeViewZ * tanHalfFov * defaultAspectRatio)
            val ndcY = viewY / (safeViewZ * tanHalfFov)

            val projScreenX = (ndcX + 1f) / 2f
            val projScreenY = (1f - ndcY) / 2f

            val isInView = (viewZ > 0.08f) && (projScreenX in -0.15f..1.15f) && (projScreenY in -0.18f..1.18f)
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
        } else {
            _spatialAnchor.value = currentAnchor.copy(
                cameraPitchDegrees = pitch,
                cameraRollDegrees = roll
            )
        }
    }

    private fun shortestAngleDelta(current: Float, target: Float): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun orthonormalizeMatrix(m: FloatArray) {
        // 第 2 列 (-forward) 归一化
        var z0 = m[2]; var z1 = m[5]; var z2 = m[8]
        val zn = kotlin.math.sqrt(z0 * z0 + z1 * z1 + z2 * z2).coerceAtLeast(1e-5f)
        z0 /= zn; z1 /= zn; z2 /= zn

        // 第 1 列 (up) 与 z 正交后归一化
        var y0 = m[1]; var y1 = m[4]; var y2 = m[7]
        val dotYZ = y0 * z0 + y1 * z1 + y2 * z2
        y0 -= dotYZ * z0; y1 -= dotYZ * z1; y2 -= dotYZ * z2
        val yn = kotlin.math.sqrt(y0 * y0 + y1 * y1 + y2 * y2).coerceAtLeast(1e-5f)
        y0 /= yn; y1 /= yn; y2 /= yn

        // 第 0 列 right = up × z（右手系正交基）
        val x0 = y1 * z2 - y2 * z1
        val x1 = y2 * z0 - y0 * z2
        val x2 = y0 * z1 - y1 * z0

        m[0] = x0; m[3] = x1; m[6] = x2
        m[1] = y0; m[4] = y1; m[7] = y2
        m[2] = z0; m[5] = z1; m[8] = z2
    }

    /**
     * 基于传感器矩阵提取「重力校平参考系」：
     * - 正常竖持（|横滚角| <= 38°）：World +Y 严格对齐地球重力正上方，低头看桌面或微歪手机时手办始终垂直地面；
     * - 侧置横放但界面锁定竖屏（|横滚角| > 38°）：保留竖屏视觉垂直，同时仍锁定三维偏航与俯仰追踪，避免人物头部侧切出画。
     */
    private fun computeGravityLeveledAnchorMatrix(curr: FloatArray, out: FloatArray) {
        val isIdentity = kotlin.math.abs(curr[0] - 1f) < 0.04f &&
                kotlin.math.abs(curr[4] - 1f) < 0.04f &&
                kotlin.math.abs(curr[8] - 1f) < 0.04f
        if (!hasLiveSensorData && isIdentity) {
            System.arraycopy(curr, 0, out, 0, 9)
            return
        }
        val fx = -curr[2]; val fy = -curr[5]; val fz = -curr[8]
        val uxDev = curr[1]; val uyDev = curr[4]; val uzDev = curr[7]
        val rxDev = curr[0]; val ryDev = curr[3]; val rzDev = curr[6]

        // 计算水平朝向 wFwd
        var hx = fx
        var hy = fy
        var hLen = kotlin.math.sqrt(hx * hx + hy * hy)
        if (hLen < 0.08f) {
            hx = uxDev
            hy = uyDev
            hLen = kotlin.math.sqrt(hx * hx + hy * hy)
        }
        if (hLen < 1e-4f) {
            System.arraycopy(curr, 0, out, 0, 9)
            return
        }
        hx /= hLen
        hy /= hLen

        // 判断手机相对地球天顶 (0, 0, 1) 的横滚角
        val rawRollRad = atan2(rzDev.toDouble(), uzDev.toDouble())
        val rawRollDeg = kotlin.math.abs(Math.toDegrees(rawRollRad))
        val targetUpX: Float
        val targetUpY: Float
        val targetUpZ: Float
        if (rawRollDeg <= 38.0) {
            targetUpX = 0f; targetUpY = 0f; targetUpZ = 1f
        } else {
            // 侧置竖屏 UI 时沿机顶方向对齐并正交化
            val dot = uxDev * hx + uyDev * hy
            val tx = uxDev - dot * hx
            val ty = uyDev - dot * hy
            val tz = uzDev
            val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-4f)
            targetUpX = tx / tl; targetUpY = ty / tl; targetUpZ = tz / tl
        }

        // 右手正交基: right = wFwd × targetUp, up = right × wFwd, back = -wFwd
        var rightX = hy * targetUpZ
        var rightY = -hx * targetUpZ
        var rightZ = hx * targetUpY - hy * targetUpX
        val rLen = kotlin.math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ).coerceAtLeast(1e-4f)
        rightX /= rLen; rightY /= rLen; rightZ /= rLen

        val upX = -rightZ * hy
        val upY = rightZ * hx
        val upZ = rightX * hy - rightY * hx

        out[0] = rightX; out[1] = upX; out[2] = -hx
        out[3] = rightY; out[4] = upY; out[5] = -hy
        out[6] = rightZ; out[7] = upZ; out[8] = 0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 点击屏幕桌面进行真实物理 3D 空间锚定（以重力正上方为 +Y 锁定到物理桌面坐标）
     */
    fun anchorToDesktop(screenX: Float, screenY: Float) {
        computeGravityLeveledAnchorMatrix(currentRotationMatrix, anchorRotationMatrix)
        hasAnchorPose = true

        val distance = 0.65f
        val tanHalfFov = tan(Math.toRadians(verticalFovDegrees.toDouble()) / 2.0).toFloat()
        val ndcX = 2f * screenX - 1f
        val ndcY = 1f - 2f * screenY

        val camRayX = ndcX * tanHalfFov * defaultAspectRatio * distance
        val camRayY = ndcY * tanHalfFov * distance
        val camRayZ = distance // 沿着相机 Forward 方向正距离

        val r00 = anchorRotationMatrix[0]; val r01 = anchorRotationMatrix[1]; val r02 = anchorRotationMatrix[2]
        val r10 = anchorRotationMatrix[3]; val r11 = anchorRotationMatrix[4]; val r12 = anchorRotationMatrix[5]
        val r20 = anchorRotationMatrix[6]; val r21 = anchorRotationMatrix[7]; val r22 = anchorRotationMatrix[8]

        val c00 = currentRotationMatrix[0]; val c01 = currentRotationMatrix[1]; val c02 = currentRotationMatrix[2]
        val c10 = currentRotationMatrix[3]; val c11 = currentRotationMatrix[4]; val c12 = currentRotationMatrix[5]
        val c20 = currentRotationMatrix[6]; val c21 = currentRotationMatrix[7]; val c22 = currentRotationMatrix[8]

        // 锚定瞬间相机在重力校平世界系下的正交基
        val fx = -(r00 * c02 + r10 * c12 + r20 * c22)
        val fy = -(r01 * c02 + r11 * c12 + r21 * c22)
        val fz = -(r02 * c02 + r12 * c12 + r22 * c22)

        val ux = r00 * c01 + r10 * c11 + r20 * c21
        val uy = r01 * c01 + r11 * c11 + r21 * c21
        val uz = r02 * c01 + r12 * c11 + r22 * c21

        val rx = r00 * c00 + r10 * c10 + r20 * c20
        val ry = r01 * c00 + r11 * c10 + r21 * c20
        val rz = r02 * c00 + r12 * c10 + r22 * c20

        // 将点击射线转换到重力校平世界系中：P_world = camRayX*Right + camRayY*Up + camRayZ*Forward
        worldAnchorX = camRayX * rx + camRayY * ux + camRayZ * fx
        worldAnchorY = camRayX * ry + camRayY * uy + camRayZ * fy
        worldAnchorZ = camRayX * rz + camRayY * uz + camRayZ * fz

        _spatialAnchor.value = _spatialAnchor.value.copy(
            screenX = screenX,
            screenY = screenY,
            isAnchored = true,
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
            isInView = true,
            azimuthDeltaDegrees = 0f,
            distanceMeters = distance,
            cameraPitchDegrees = pitch,
            cameraRollDegrees = roll,
            cameraFocalDistance = 10f,
            planeTypeDescription = "书桌实景 (重力校平 3D 空间锁定)"
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

