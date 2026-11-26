package com.persona.ar.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.persona.ar.character.skeleton.SkeletonStance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 零 GMS 依赖的通用 6DoF 视觉-惯性空间锚定与追踪引擎。
 *
 * 核心能力：
 * 1. 基于 6 轴 `TYPE_GAME_ROTATION_VECTOR`（无磁偏漂移）与 `SpatialMath3D` 重力校平参考系，
 *    世界 +Y 轴严格对齐地球重力正上方；
 * 2. 融合 `CameraEnvironmentalAnalyzer` 稀疏光流观测，在手机绕桌面移动时反解真实 6DoF 相机轨道位置，
 *    支持拿着手机绕模型所在位置 360° 观察正面/侧面/背面，模型稳固停留于物理桌面原位；
 * 3. 射线-水平面求交摆放与拖拽重定位：保证屏幕拖拽指哪拖到哪，松手零回弹、零乱跑；
 * 4. 线程安全状态锁 (`stateLock`) 彻底消除传感器线程、光流线程与 UI 线程的数据竞争。
 */
class UniversalSensorSurfaceTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gameRotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _spatialAnchor = MutableStateFlow(ARSpatialAnchor())
    val spatialAnchor: StateFlow<ARSpatialAnchor> = _spatialAnchor.asStateFlow()

    private val _trackingStatus = MutableStateFlow(ARTrackingStatus.PLANE_DETECTED)
    val trackingStatus: StateFlow<ARTrackingStatus> = _trackingStatus.asStateFlow()

    private val stateLock = Any()

    // 姿态滤波值
    private var pitch = 45f
    private var roll = 0f
    private var yaw = 0f
    private val rotAlpha = 0.28f

    // 传感器 3×3 旋转矩阵 (行主序 Device -> ENU)
    private val rawRotationMatrix = FloatArray(9)
    private val currentRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private val orientationAngles = FloatArray(3)

    // 锚定参考坐标系
    private val anchorRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private var anchorYawRad = 0f
    private var useGravityLeveling = true
    private var hasAnchorPose = false
    private var hasLiveSensorData = false

    // 相机实时世界坐标系正交基 & 6DoF 相机位置
    private val camRight = floatArrayOf(1f, 0f, 0f)
    private val camUp = floatArrayOf(0f, 1f, 0f)
    private val camForward = floatArrayOf(0f, 0f, -1f)
    private val camPos = floatArrayOf(0f, 0f, 0f)
    private val targetCamPos = floatArrayOf(0f, 0f, 0f)

    // 世界空间模型脚底坐标 (支撑桌面为水平面 Y = planeY)
    private var worldAnchorX = 0f
    private var worldAnchorY = -0.12f
    private var worldAnchorZ = -0.65f
    private var planeY = -0.12f

    // 视觉追踪融合状态
    private var visualEstimatedDistance = 0.65f
    private var currentCamHeightAbovePlane = 0.12f
    private var visualConfidence = 0f
    private var isDragging = false
    private var dragGrabOffsetX = 0f
    private var dragGrabOffsetZ = 0f

    // 真实相机内参 (由 CameraXView 实时注入)
    private var verticalFovDegrees = 62f
    private var viewportAspect = 1080f / 2340f

    // 复用临时向量
    private val tmpRay = FloatArray(3)
    private val tmpHit = FloatArray(3)
    private val tmpProj = FloatArray(3)

    fun start() {
        val sensorToUse = gameRotationVectorSensor ?: rotationVectorSensor ?: gravitySensor ?: accelerometer
        sensorToUse?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    /** 由 CameraXView / 视口更新真实相机垂直视场角与视口宽高比 */
    fun updateCameraIntrinsics(effectiveVerticalFovDeg: Float, aspect: Float) {
        synchronized(stateLock) {
            verticalFovDegrees = effectiveVerticalFovDeg.coerceIn(35f, 88f)
            viewportAspect = aspect.coerceIn(0.35f, 2.5f)
        }
    }

    /** 线程安全快照当前相机世界正交基（供 CameraX 帧分析做 IMU 旋转先验预测） */
    fun snapshotImuWorldBasis(outRight: FloatArray, outUp: FloatArray, outForward: FloatArray): Boolean {
        synchronized(stateLock) {
            updateCurrentCameraBasisLocked()
            System.arraycopy(camRight, 0, outRight, 0, 3)
            System.arraycopy(camUp, 0, outUp, 0, 3)
            System.arraycopy(camForward, 0, outForward, 0, 3)
            return true
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                val rot = FloatArray(9)
                val ori = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, ori)
                val curYaw = Math.toDegrees(ori[0].toDouble()).toFloat()
                val rawDownPitch = (-Math.toDegrees(ori[1].toDouble()).toFloat()).coerceIn(0f, 90f)
                val curRoll = Math.toDegrees(ori[2].toDouble()).toFloat()
                synchronized(stateLock) {
                    hasLiveSensorData = true
                }
                applyOrientationFilter(rot, curYaw, rawDownPitch, curRoll)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val downPitch = Math.toDegrees(atan2(gz.toDouble(), gy.toDouble())).toFloat().coerceIn(0f, 90f)
                val curRoll = Math.toDegrees(atan2(-gx.toDouble(), sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
                synchronized(stateLock) {
                    applyOrientationFilterLocked(currentRotationMatrix, yaw, downPitch, curRoll)
                }
            }
        }
    }

    internal fun applyOrientationFilter(rawRot: FloatArray, targetYaw: Float, targetPitch: Float, targetRoll: Float) {
        synchronized(stateLock) {
            // 单元测试直接调用此方法注入非单位旋转矩阵时，自动标记具备真实姿态数据
            if (!isNearIdentity(rawRot)) {
                hasLiveSensorData = true
            }
            applyOrientationFilterLocked(rawRot, targetYaw, targetPitch, targetRoll)
        }
    }

    private fun applyOrientationFilterLocked(rawRot: FloatArray, targetYaw: Float, targetPitch: Float, targetRoll: Float) {
        if (abs(targetPitch - pitch) > 0.18f) {
            pitch += rotAlpha * (targetPitch - pitch)
        }
        if (abs(targetRoll - roll) > 0.18f) {
            roll += rotAlpha * (targetRoll - roll)
        }
        yaw += rotAlpha * shortestAngleDelta(yaw, targetYaw)

        for (i in 0 until 9) {
            currentRotationMatrix[i] += rotAlpha * (rawRot[i] - currentRotationMatrix[i])
        }
        orthonormalizeEnuMatrix(currentRotationMatrix)

        // 若最初侧置桌边锚定、随后用户拿起手机竖屏手持，无缝过渡至严格重力校平
        if (hasAnchorPose && !useGravityLeveling && hasLiveSensorData) {
            val rawRollDeg = abs(Math.toDegrees(atan2(currentRotationMatrix[6].toDouble(), currentRotationMatrix[7].toDouble())))
            if (rawRollDeg < 35.0) {
                anchorYawRad = SpatialMath3D.extractAnchorYawRad(currentRotationMatrix)
                useGravityLeveling = true
            }
        }

        // 相机位置平滑追随目标轨道位置（消除 30Hz 视觉帧与 200Hz IMU 间的微锯齿）
        if (hasAnchorPose && !isDragging) {
            val posLerp = 0.25f
            camPos[0] += (targetCamPos[0] - camPos[0]) * posLerp
            camPos[1] += (targetCamPos[1] - camPos[1]) * posLerp
            camPos[2] += (targetCamPos[2] - camPos[2]) * posLerp
        }

        val currentAnchor = _spatialAnchor.value
        if (currentAnchor.isAnchored && hasAnchorPose) {
            updateCurrentCameraBasisLocked()
            emitProjectedStateLocked(currentAnchor)
        } else {
            _spatialAnchor.value = currentAnchor.copy(
                cameraPitchDegrees = pitch,
                cameraRollDegrees = roll,
                effectiveVerticalFovDegrees = verticalFovDegrees
            )
        }
    }

    private fun updateCurrentCameraBasisLocked() {
        if (!hasAnchorPose || !hasLiveSensorData) {
            camRight[0] = 1f; camRight[1] = 0f; camRight[2] = 0f
            camUp[0] = 0f; camUp[1] = 1f; camUp[2] = 0f
            camForward[0] = 0f; camForward[1] = 0f; camForward[2] = -1f
            return
        }
        if (useGravityLeveling) {
            SpatialMath3D.sensorMatrixToWorldBasis(currentRotationMatrix, anchorYawRad, camRight, camUp, camForward)
        } else {
            SpatialMath3D.sensorMatrixToAnchoredBasis(currentRotationMatrix, anchorRotationMatrix, camRight, camUp, camForward)
        }
    }

    /**
     * 接收来自 CameraX 稀疏光流的桌面锚点屏幕观测，融合 IMU 反解 6DoF 相机位置（支持 360° 绕模型走动）。
     */
    fun onVisualAnchorObserved(
        obs: VisualAnchorObservation,
        basisRight: FloatArray,
        basisUp: FloatArray,
        basisForward: FloatArray
    ) {
        synchronized(stateLock) {
            val currentAnchor = _spatialAnchor.value
            if (!currentAnchor.isAnchored || !hasAnchorPose || isDragging) return

            if (obs.isLost || obs.inlierCount < 3) {
                visualConfidence = (visualConfidence * 0.7f).coerceAtLeast(0f)
                return
            }

            // 视觉尺度更新估计距离
            val scaleFactor = if (obs.inlierCount >= 4) obs.scaleRatio.coerceIn(0.95f, 1.05f) else 1f
            visualEstimatedDistance = (visualEstimatedDistance / scaleFactor).coerceIn(0.24f, 2.20f)

            // 构造此刻从相机指向视觉追踪桌面锚点的世界射线
            SpatialMath3D.screenToWorldRay(
                screenX = obs.trackedScreenX,
                screenY = obs.trackedScreenY,
                verticalFovDeg = verticalFovDegrees,
                aspect = viewportAspect,
                camRight = basisRight,
                camUp = basisUp,
                camForward = basisForward,
                outDir = tmpRay
            )

            // 融合平面高度约束与尺度距离：俯视时遵循桌面平面交距，平视时无奇异点平稳衔接
            val dirY = tmpRay[1]
            val targetDist: Float
            if (dirY < -0.22f && currentCamHeightAbovePlane > 0.14f) {
                val planeDist = (currentCamHeightAbovePlane / (-dirY)).coerceIn(0.24f, 2.20f)
                targetDist = planeDist * 0.68f + visualEstimatedDistance * 0.32f
                // 极慢速适应用户抬高/蹲低手机引起的高度变化
                val observedH = visualEstimatedDistance * (-dirY)
                currentCamHeightAbovePlane = (currentCamHeightAbovePlane * 0.94f + observedH * 0.06f).coerceIn(0.14f, 1.50f)
            } else {
                targetDist = visualEstimatedDistance
            }

            // C_target = M - targetDist * d_world
            targetCamPos[0] = worldAnchorX - targetDist * tmpRay[0]
            targetCamPos[1] = max(worldAnchorY - targetDist * tmpRay[1], planeY + 0.06f)
            targetCamPos[2] = worldAnchorZ - targetDist * tmpRay[2]

            visualConfidence = obs.confidence
        }
    }

    /**
     * 点击屏幕桌面执行 3D 物理空间锚定：固定支撑水平面 `Y = planeY`，放置脚底锚点 `M`。
     */
    fun anchorToDesktop(screenX: Float, screenY: Float) {
        synchronized(stateLock) {
            val sx = screenX.coerceIn(0.05f, 0.95f)
            val sy = screenY.coerceIn(0.15f, 0.95f)

            if (hasLiveSensorData) {
                val rawRollDeg = abs(Math.toDegrees(atan2(currentRotationMatrix[6].toDouble(), currentRotationMatrix[7].toDouble())))
                if (rawRollDeg <= 45.0) {
                    useGravityLeveling = true
                    anchorYawRad = SpatialMath3D.extractAnchorYawRad(currentRotationMatrix)
                } else {
                    useGravityLeveling = false
                    System.arraycopy(currentRotationMatrix, 0, anchorRotationMatrix, 0, 9)
                }
            } else {
                useGravityLeveling = false
                System.arraycopy(currentRotationMatrix, 0, anchorRotationMatrix, 0, 9)
            }

            hasAnchorPose = true
            camPos[0] = 0f; camPos[1] = 0f; camPos[2] = 0f
            targetCamPos[0] = 0f; targetCamPos[1] = 0f; targetCamPos[2] = 0f
            updateCurrentCameraBasisLocked()

            val initialDistance = 0.65f
            visualEstimatedDistance = initialDistance
            SpatialMath3D.screenToWorldRay(
                screenX = sx,
                screenY = sy,
                verticalFovDeg = verticalFovDegrees,
                aspect = viewportAspect,
                camRight = camRight,
                camUp = camUp,
                camForward = camForward,
                outDir = tmpRay
            )

            worldAnchorX = camPos[0] + tmpRay[0] * initialDistance
            worldAnchorY = camPos[1] + tmpRay[1] * initialDistance
            worldAnchorZ = camPos[2] + tmpRay[2] * initialDistance
            planeY = worldAnchorY
            currentCamHeightAbovePlane = (camPos[1] - planeY).coerceAtLeast(0.18f)

            // 锚定时记录世界主光方位：若已有屏幕光源信号则投影成物理世界向量并锁定
            val cur = _spatialAnchor.value
            val ldx = cur.lightDirectionX
            val ldy = cur.lightDirectionY
            val wlX = ldx * camRight[0] - 0.55f * camForward[0] + (-0.3f) * camUp[0]
            val wlY = -(1.15f + (-ldy).coerceIn(0f, 1f) * 0.9f)
            val wlZ = ldx * camRight[2] - 0.55f * camForward[2] + (-0.3f) * camUp[2]
            val wlLen = sqrt(wlX * wlX + wlY * wlY + wlZ * wlZ).coerceAtLeast(1e-4f)

            _spatialAnchor.value = cur.copy(
                screenX = sx,
                screenY = sy,
                isAnchored = true,
                isWorldAnchored = true,
                worldAnchorX = worldAnchorX,
                worldAnchorY = worldAnchorY,
                worldAnchorZ = worldAnchorZ,
                cameraPosX = camPos[0],
                cameraPosY = camPos[1],
                cameraPosZ = camPos[2],
                cameraRightX = camRight[0],
                cameraRightY = camRight[1],
                cameraRightZ = camRight[2],
                cameraForwardX = camForward[0],
                cameraForwardY = camForward[1],
                cameraForwardZ = camForward[2],
                cameraUpX = camUp[0],
                cameraUpY = camUp[1],
                cameraUpZ = camUp[2],
                effectiveVerticalFovDegrees = verticalFovDegrees,
                hasWorldLightDir = true,
                worldLightDirX = wlX / wlLen,
                worldLightDirY = wlY / wlLen,
                worldLightDirZ = wlZ / wlLen,
                isInView = true,
                azimuthDeltaDegrees = 0f,
                orbitRelativeYawDegrees = 0f,
                distanceMeters = initialDistance,
                cameraPitchDegrees = pitch,
                cameraRollDegrees = roll,
                planeTypeDescription = "桌面实景 6DoF 空间锁定"
            )
            _trackingStatus.value = ARTrackingStatus.ANCHORED
        }
    }

    /**
     * 开始在物理水平桌面上拖拽模型：在按下点求交平面记录抓取偏移，避免瞬移。
     */
    fun beginDrag(screenX: Float, screenY: Float) {
        synchronized(stateLock) {
            if (!hasAnchorPose) return
            isDragging = true
            updateCurrentCameraBasisLocked()
            SpatialMath3D.screenToWorldRay(
                screenX, screenY, verticalFovDegrees, viewportAspect,
                camRight, camUp, camForward, tmpRay
            )
            SpatialMath3D.intersectRayWithHorizontalPlane(
                camPos[0], camPos[1], camPos[2], tmpRay,
                planeY, visualEstimatedDistance, outPoint = tmpHit
            )
            dragGrabOffsetX = worldAnchorX - tmpHit[0]
            dragGrabOffsetZ = worldAnchorZ - tmpHit[2]
            _spatialAnchor.value = _spatialAnchor.value.copy(isDragging = true)
        }
    }

    /**
     * 拖拽移动模型：将当前手指屏幕绝对坐标射线求交至同一物理桌面平面 `Y = planeY`，
     * 绝不重置世界坐标基，指哪拖到哪，绝不乱跑或弹回。
     */
    fun dragTo(screenX: Float, screenY: Float): ARSpatialAnchor {
        synchronized(stateLock) {
            if (!hasAnchorPose) return _spatialAnchor.value
            if (!isDragging) beginDrag(screenX, screenY)

            updateCurrentCameraBasisLocked()
            val clampedX = screenX.coerceIn(0.04f, 0.96f)
            val clampedY = screenY.coerceIn(0.12f, 0.94f)
            SpatialMath3D.screenToWorldRay(
                clampedX, clampedY, verticalFovDegrees, viewportAspect,
                camRight, camUp, camForward, tmpRay
            )
            SpatialMath3D.intersectRayWithHorizontalPlane(
                camPos[0], camPos[1], camPos[2], tmpRay,
                planeY, visualEstimatedDistance, outPoint = tmpHit
            )

            var nextX = tmpHit[0] + dragGrabOffsetX
            var nextZ = tmpHit[2] + dragGrabOffsetZ

            // 限制相对于当前相机的合理水平半径 (0.24m..2.60m)，避免平视边缘时拉到过远
            val dx = nextX - camPos[0]
            val dz = nextZ - camPos[2]
            val hDist = hypot(dx.toDouble(), dz.toDouble()).toFloat()
            if (hDist > 2.60f) {
                val k = 2.60f / hDist
                nextX = camPos[0] + dx * k
                nextZ = camPos[2] + dz * k
            } else if (hDist < 0.22f) {
                val safeLen = hDist.coerceAtLeast(1e-4f)
                val k = 0.22f / safeLen
                nextX = camPos[0] + (if (hDist > 1e-4f) dx * k else camForward[0] * 0.22f)
                nextZ = camPos[2] + (if (hDist > 1e-4f) dz * k else camForward[2] * 0.22f)
            }

            worldAnchorX = nextX
            worldAnchorY = planeY
            worldAnchorZ = nextZ

            val prevAnchor = _spatialAnchor.value
            val prevScreenX = prevAnchor.screenX
            emitProjectedStateLocked(prevAnchor)
            val after = _spatialAnchor.value
            val speedX = (after.screenX - prevScreenX) * 260f
            val sway = (-speedX).coerceIn(-16f, 16f)
            val updated = after.copy(isDragging = true, inertialSwayAngle = sway)
            _spatialAnchor.value = updated
            return updated
        }
    }

    /** 结束拖拽：稳停在用户松手位置，更新视觉参考距离 */
    fun endDrag(): ARSpatialAnchor {
        synchronized(stateLock) {
            isDragging = false
            val dx = worldAnchorX - camPos[0]
            val dy = worldAnchorY - camPos[1]
            val dz = worldAnchorZ - camPos[2]
            visualEstimatedDistance = sqrt(dx * dx + dy * dy + dz * dz).coerceIn(0.24f, 2.60f)
            targetCamPos[0] = camPos[0]
            targetCamPos[1] = camPos[1]
            targetCamPos[2] = camPos[2]
            val updated = _spatialAnchor.value.copy(
                isDragging = false,
                inertialSwayAngle = 0f,
                distanceMeters = visualEstimatedDistance
            )
            _spatialAnchor.value = updated
            return updated
        }
    }

    private fun emitProjectedStateLocked(base: ARSpatialAnchor) {
        val isInView = SpatialMath3D.projectWorldToScreen(
            wx = worldAnchorX,
            wy = worldAnchorY,
            wz = worldAnchorZ,
            camPosX = camPos[0],
            camPosY = camPos[1],
            camPosZ = camPos[2],
            camRight = camRight,
            camUp = camUp,
            camForward = camForward,
            verticalFovDeg = verticalFovDegrees,
            aspect = viewportAspect,
            outScreen = tmpProj
        )
        val projX = tmpProj[0]
        val projY = tmpProj[1]

        val dx = worldAnchorX - camPos[0]
        val dy = worldAnchorY - camPos[1]
        val dz = worldAnchorZ - camPos[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz).coerceIn(0.22f, 3.0f)

        val azimuthDelta = SpatialMath3D.computeAzimuthDeltaDegrees(
            worldAnchorX, worldAnchorY, worldAnchorZ,
            camPos[0], camPos[1], camPos[2], camRight, camForward
        )

        // 计算相机绕模型公转时的相对水平角度（用于 360° 观察时眼神在合理范围内随动）
        val vx = camPos[0] - worldAnchorX
        val vz = camPos[2] - worldAnchorZ
        val yawRad = Math.toRadians(base.rotationYaw.toDouble()).toFloat()
        // 模型自身正面 (+Z rotated by yaw)
        val frontX = sin(yawRad)
        val frontZ = cos(yawRad)
        val rightX = cos(yawRad)
        val rightZ = -sin(yawRad)
        val dotFront = vx * frontX + vz * frontZ
        val dotRight = vx * rightX + vz * rightZ
        val orbitRelYaw = Math.toDegrees(atan2(dotRight.toDouble(), dotFront.toDouble())).toFloat()

        // 结合画面偏移与绕圈角度计算自然眼神偏移：正面/侧面柔和看镜头，转到背后(>95°)眼神回正避免扭脖
        val absOrbit = abs(orbitRelYaw)
        val gazeAtten = when {
            absOrbit <= 70f -> 1f
            absOrbit >= 105f -> 0f
            else -> 1f - (absOrbit - 70f) / 35f
        }
        val safeZ = tmpProj[2].coerceAtLeast(0.12f)
        val viewX = dx * camRight[0] + dy * camRight[1] + dz * camRight[2]
        val viewY = dx * camUp[0] + dy * camUp[1] + dz * camUp[2]
        val gazeX = ((-viewX / safeZ) * 0.7f + (orbitRelYaw / 75f) * 0.55f).coerceIn(-1f, 1f) * gazeAtten
        val gazeY = (-viewY / safeZ).coerceIn(-1f, 1f) * gazeAtten

        _spatialAnchor.value = base.copy(
            screenX = projX,
            screenY = projY,
            distanceMeters = dist,
            isWorldAnchored = true,
            worldAnchorX = worldAnchorX,
            worldAnchorY = worldAnchorY,
            worldAnchorZ = worldAnchorZ,
            cameraPosX = camPos[0],
            cameraPosY = camPos[1],
            cameraPosZ = camPos[2],
            cameraRightX = camRight[0],
            cameraRightY = camRight[1],
            cameraRightZ = camRight[2],
            cameraForwardX = camForward[0],
            cameraForwardY = camForward[1],
            cameraForwardZ = camForward[2],
            cameraUpX = camUp[0],
            cameraUpY = camUp[1],
            cameraUpZ = camUp[2],
            effectiveVerticalFovDegrees = verticalFovDegrees,
            isInView = isInView,
            azimuthDeltaDegrees = azimuthDelta,
            orbitRelativeYawDegrees = orbitRelYaw,
            visualTrackingConfidence = visualConfidence,
            cameraGazeOffsetX = gazeX,
            cameraGazeOffsetY = gazeY,
            cameraPitchDegrees = pitch,
            cameraRollDegrees = roll
        )
    }

    /** 供视觉光流模块更新的环境光学字段写回（线程安全，不覆盖 6DoF 位姿） */
    fun updateOpticalMetadata(profile: EnvironmentalOpticalProfile) {
        synchronized(stateLock) {
            val cur = _spatialAnchor.value
            val effectiveLighting = (profile.luminance * 0.42f + profile.localSurfaceLuminance * 0.58f).coerceIn(0.18f, 1.35f)
            // 若强光源较明确且尚未锁定世界方向，或置信度显著高时极缓慢校准世界方向光
            var nextHasWorldLight = cur.hasWorldLightDir
            var wlX = cur.worldLightDirX
            var wlY = cur.worldLightDirY
            var wlZ = cur.worldLightDirZ
            if (cur.isAnchored && profile.lightDirectionalStrength > 0.35f) {
                val candX = profile.lightDirectionX * camRight[0] - 0.55f * camForward[0]
                val candY = -(1.15f + (-profile.lightDirectionY).coerceIn(0f, 1f) * 0.9f)
                val candZ = profile.lightDirectionX * camRight[2] - 0.55f * camForward[2]
                val len = sqrt(candX * candX + candY * candY + candZ * candZ).coerceAtLeast(1e-4f)
                val nx = candX / len; val ny = candY / len; val nz = candZ / len
                if (!nextHasWorldLight) {
                    nextHasWorldLight = true
                    wlX = nx; wlY = ny; wlZ = nz
                } else {
                    // 极慢阻尼（0.02），只响应真实室内灯切换，绕圈走动时阴影稳固不动
                    wlX += (nx - wlX) * 0.02f
                    wlY += (ny - wlY) * 0.02f
                    wlZ += (nz - wlZ) * 0.02f
                    val nLen = sqrt(wlX * wlX + wlY * wlY + wlZ * wlZ).coerceAtLeast(1e-4f)
                    wlX /= nLen; wlY /= nLen; wlZ /= nLen
                }
            }

            _spatialAnchor.value = cur.copy(
                lightIntensity = effectiveLighting,
                isLowLight = profile.isLowLight,
                ambientColorTemp = profile.colorTemp,
                ambientTintRed = profile.tintR,
                ambientTintGreen = profile.tintG,
                ambientTintBlue = profile.tintB,
                envTopRed = profile.envTopR,
                envTopGreen = profile.envTopG,
                envTopBlue = profile.envTopB,
                envBottomRed = profile.envBottomR,
                envBottomGreen = profile.envBottomG,
                envBottomBlue = profile.envBottomB,
                envLeftRed = profile.envLeftR,
                envLeftGreen = profile.envLeftG,
                envLeftBlue = profile.envLeftB,
                envRightRed = profile.envRightR,
                envRightGreen = profile.envRightG,
                envRightBlue = profile.envRightB,
                sensorGrainIntensity = profile.sensorGrainIntensity,
                bloomIntensity = profile.bloomBleedFactor,
                localSurfaceLuminance = profile.localSurfaceLuminance,
                localSurfaceTintRed = profile.localSurfaceTintR,
                localSurfaceTintGreen = profile.localSurfaceTintG,
                localSurfaceTintBlue = profile.localSurfaceTintB,
                lightDirectionX = profile.lightDirectionX,
                lightDirectionY = profile.lightDirectionY,
                lightDirectionalStrength = profile.lightDirectionalStrength,
                hasWorldLightDir = nextHasWorldLight,
                worldLightDirX = wlX,
                worldLightDirY = wlY,
                worldLightDirZ = wlZ,
                deskTextureConfidence = profile.deskTextureConfidence,
                lensVignetteFactor = profile.lensVignetteFactor
            )
        }
    }

    fun updateScale(scaleMultiplier: Float) {
        synchronized(stateLock) {
            val current = _spatialAnchor.value.scaleFactor
            val next = (current * scaleMultiplier).coerceIn(0.4f, 2.5f)
            _spatialAnchor.value = _spatialAnchor.value.copy(scaleFactor = next)
        }
    }

    fun updateRotation(yawDeltaDegree: Float) {
        synchronized(stateLock) {
            val current = _spatialAnchor.value.rotationYaw
            val next = (current + yawDeltaDegree) % 360f
            _spatialAnchor.value = _spatialAnchor.value.copy(rotationYaw = next)
        }
    }

    fun updateSkeletonStance(stance: SkeletonStance) {
        synchronized(stateLock) {
            _spatialAnchor.value = _spatialAnchor.value.copy(skeletonStance = stance)
        }
    }

    fun updateCameraFocus(isFocusing: Boolean, timestamp: Long? = null) {
        synchronized(stateLock) {
            val cur = _spatialAnchor.value
            _spatialAnchor.value = cur.copy(
                isCameraFocusing = isFocusing,
                cameraFocusTimestamp = timestamp ?: cur.cameraFocusTimestamp
            )
        }
    }

    fun resetAnchor() {
        synchronized(stateLock) {
            val preservedStance = _spatialAnchor.value.skeletonStance
            hasAnchorPose = false
            isDragging = false
            visualConfidence = 0f
            camPos[0] = 0f; camPos[1] = 0f; camPos[2] = 0f
            targetCamPos[0] = 0f; targetCamPos[1] = 0f; targetCamPos[2] = 0f
            _spatialAnchor.value = ARSpatialAnchor(
                screenX = 0.5f,
                screenY = 0.65f,
                distanceMeters = 0.65f,
                scaleFactor = 1.0f,
                rotationYaw = 0f,
                skeletonStance = preservedStance,
                isAnchored = false,
                isWorldAnchored = false,
                isInView = true,
                azimuthDeltaDegrees = 0f,
                effectiveVerticalFovDegrees = verticalFovDegrees,
                planeTypeDescription = "未锚定"
            )
            _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun shortestAngleDelta(current: Float, target: Float): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun isNearIdentity(m: FloatArray): Boolean {
        return abs(m[0] - 1f) < 0.03f && abs(m[4] - 1f) < 0.03f && abs(m[8] - 1f) < 0.03f &&
                abs(m[1]) < 0.03f && abs(m[2]) < 0.03f && abs(m[3]) < 0.03f &&
                abs(m[5]) < 0.03f && abs(m[6]) < 0.03f && abs(m[7]) < 0.03f
    }

    private fun orthonormalizeEnuMatrix(m: FloatArray) {
        var z0 = m[2]; var z1 = m[5]; var z2 = m[8]
        val zn = sqrt(z0 * z0 + z1 * z1 + z2 * z2).coerceAtLeast(1e-5f)
        z0 /= zn; z1 /= zn; z2 /= zn

        var y0 = m[1]; var y1 = m[4]; var y2 = m[7]
        val dotYZ = y0 * z0 + y1 * z1 + y2 * z2
        y0 -= dotYZ * z0; y1 -= dotYZ * z1; y2 -= dotYZ * z2
        val yn = sqrt(y0 * y0 + y1 * y1 + y2 * y2).coerceAtLeast(1e-5f)
        y0 /= yn; y1 /= yn; y2 /= yn

        val x0 = y1 * z2 - y2 * z1
        val x1 = y2 * z0 - y0 * z2
        val x2 = y0 * z1 - y1 * z0

        m[0] = x0; m[3] = x1; m[6] = x2
        m[1] = y0; m[4] = y1; m[7] = y2
        m[2] = z0; m[5] = z1; m[8] = z2
    }
}
