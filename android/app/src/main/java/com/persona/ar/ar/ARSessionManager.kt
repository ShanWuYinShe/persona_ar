package com.persona.ar.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * AR 空间追踪与物理平面会话管理器
 * 负责 ARCore 平面检测、空间命中测试 (HitTest)、光照估计与手势位姿解算
 */
class ARSessionManager(private val context: Context) {

    private var session: Session? = null
    private var anchor: Anchor? = null

    private val _trackingStatus = MutableStateFlow(ARTrackingStatus.INITIALIZING)
    val trackingStatus: StateFlow<ARTrackingStatus> = _trackingStatus.asStateFlow()

    private val _spatialAnchor = MutableStateFlow(ARSpatialAnchor())
    val spatialAnchor: StateFlow<ARSpatialAnchor> = _spatialAnchor.asStateFlow()

    private val _reticleState = MutableStateFlow(ARReticleState())
    val reticleState: StateFlow<ARReticleState> = _reticleState.asStateFlow()

    private var isARCoreAvailable = false

    fun initSession(): Boolean {
        isARCoreAvailable = ARCoreHelper.isARCoreSupported(context)
        if (!isARCoreAvailable) {
            Log.i("ARSessionManager", "Device does not support ARCore, falling back to 2.5D spatial simulator")
            _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
            return false
        }

        return try {
            session = ARCoreHelper.createSession(context)
            session?.let { s ->
                val config = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                }
                s.configure(config)
            }
            _trackingStatus.value = ARTrackingStatus.SEARCHING_PLANE
            true
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Failed to init ARCore session: ${e.message}")
            _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
            false
        }
    }

    fun onResume() {
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e("ARSessionManager", "Camera not available: ${e.message}")
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Session resume error: ${e.message}")
        }
    }

    fun onPause() {
        try {
            session?.pause()
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Session pause error: ${e.message}")
        }
    }

    /**
     * 实时解算当前帧 (在 ARSurface 或传感器循环中驱动)
     */
    fun processFrame(frame: Frame?) {
        if (frame == null) {
            // 离线/模拟兜底驱动
            simulateSpatialTracking()
            return
        }

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            _trackingStatus.value = ARTrackingStatus.TRACKING_LOST
            return
        }

        // 1. 提取现实光照强度 (0f..1f)
        val lightIntensity = try {
            (frame.lightEstimate.pixelIntensity / 1.0f).coerceIn(0.2f, 1.0f)
        } catch (e: Exception) {
            0.85f
        }

        // 2. 检查检测到的平面
        val planes = session?.getAllTrackables(Plane::class.java) ?: emptyList()
        val hasValidPlane = planes.any { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }

        if (!spatialAnchor.value.isAnchored) {
            if (hasValidPlane) {
                _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
                _reticleState.value = _reticleState.value.copy(
                    visible = true,
                    screenX = 0.5f,
                    screenY = 0.65f
                )
            } else {
                _trackingStatus.value = ARTrackingStatus.SEARCHING_PLANE
                _reticleState.value = _reticleState.value.copy(visible = false)
            }
        } else {
            _trackingStatus.value = ARTrackingStatus.ANCHORED
            _reticleState.value = _reticleState.value.copy(visible = false)

            // 计算与摄像机的相对物理距离
            anchor?.pose?.let { anchorPose ->
                val cameraPose = camera.pose
                val dx = anchorPose.tx() - cameraPose.tx()
                val dy = anchorPose.ty() - cameraPose.ty()
                val dz = anchorPose.tz() - cameraPose.tz()
                val distance = sqrt(dx * dx + dy * dy + dz * dz)

                _spatialAnchor.value = _spatialAnchor.value.copy(
                    distanceMeters = (distance * 10f).toInt() / 10f,
                    lightIntensity = lightIntensity
                )
            }
        }
    }

    /**
     * 点击屏幕进行命中测试并锚定角色
     */
    fun placeAnchor(tapScreenX: Float, tapScreenY: Float, widthPx: Float, heightPx: Float): Boolean {
        val s = session
        if (s != null) {
            val frame = try { s.update() } catch (e: Exception) { null }
            if (frame != null) {
                val hitList = frame.hitTest(tapScreenX * widthPx, tapScreenY * heightPx)
                for (hit in hitList) {
                    val trackable = hit.trackable
                    if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                        anchor?.detach()
                        val newAnchor = hit.createAnchor()
                        anchor = newAnchor

                        val planeName = if (trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING) "物理桌面" else "地面表面"
                        _spatialAnchor.value = _spatialAnchor.value.copy(
                            screenX = tapScreenX,
                            screenY = tapScreenY,
                            isAnchored = true,
                            planeTypeDescription = planeName
                        )
                        _trackingStatus.value = ARTrackingStatus.ANCHORED
                        return true
                    }
                }
            }
        }

        // 模拟/快速降级命中
        _spatialAnchor.value = _spatialAnchor.value.copy(
            screenX = tapScreenX,
            screenY = tapScreenY,
            isAnchored = true,
            distanceMeters = 0.75f,
            planeTypeDescription = "实景平面"
        )
        _trackingStatus.value = ARTrackingStatus.ANCHORED
        return true
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
        anchor?.detach()
        anchor = null
        _spatialAnchor.value = ARSpatialAnchor(
            screenX = 0.5f,
            screenY = 0.65f,
            distanceMeters = 0.85f,
            scaleFactor = 1.0f,
            rotationYaw = 0f,
            isAnchored = false
        )
        _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
    }

    private fun simulateSpatialTracking() {
        if (!_spatialAnchor.value.isAnchored) {
            _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
        }
    }

    fun destroy() {
        try {
            anchor?.detach()
            anchor = null
            session?.close()
            session = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
