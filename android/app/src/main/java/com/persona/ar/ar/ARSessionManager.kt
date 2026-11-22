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
 * 原生集成零 GMS 依赖的通用实景桌面视差追踪器 (Universal Sensor Surface Tracker)
 * 兼容 Google ARCore (有 GMS 时可选)，在无 GMS 国内设备上 100% 自动优雅运行
 */
class ARSessionManager(private val context: Context) {

    private var session: Session? = null
    private var anchor: Anchor? = null

    // 核心通用姿态与视差追踪器 (零 GMS 依赖)
    val universalTracker = UniversalSensorSurfaceTracker(context)

    private val _trackingStatus = MutableStateFlow(ARTrackingStatus.PLANE_DETECTED)
    val trackingStatus: StateFlow<ARTrackingStatus> = _trackingStatus.asStateFlow()

    private val _spatialAnchor = MutableStateFlow(
        ARSpatialAnchor(
            trackerType = ARTrackerType.UNIVERSAL_SENSOR,
            planeTypeDescription = "书桌实景 (零 GMS 通用视差)"
        )
    )
    val spatialAnchor: StateFlow<ARSpatialAnchor> = _spatialAnchor.asStateFlow()

    private var isARCoreAvailable = false

    fun initSession(): Boolean {
        universalTracker.start()

        isARCoreAvailable = ARCoreHelper.isARCoreSupported(context)
        if (!isARCoreAvailable) {
            Log.i("ARSessionManager", "Running in Universal Sensor mode (100% offline, zero GMS dependency)")
            _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
            _spatialAnchor.value = _spatialAnchor.value.copy(
                trackerType = ARTrackerType.UNIVERSAL_SENSOR,
                planeTypeDescription = "书桌实景 (零 GMS 通用视差)"
            )
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
            _spatialAnchor.value = _spatialAnchor.value.copy(
                trackerType = ARTrackerType.GOOGLE_ARCORE,
                planeTypeDescription = "ARCore 物理平面"
            )
            true
        } catch (e: Exception) {
            Log.w("ARSessionManager", "ARCore session failed, seamlessly fallback to Universal Sensor: ${e.message}")
            _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
            _spatialAnchor.value = _spatialAnchor.value.copy(
                trackerType = ARTrackerType.UNIVERSAL_SENSOR,
                planeTypeDescription = "书桌实景 (零 GMS 通用视差)"
            )
            false
        }
    }

    fun onResume() {
        universalTracker.start()
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e("ARSessionManager", "Camera not available: ${e.message}")
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Session resume error: ${e.message}")
        }
    }

    fun onPause() {
        universalTracker.stop()
        try {
            session?.pause()
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Session pause error: ${e.message}")
        }
    }

    /**
     * 点击屏幕桌面执行空间锁定
     */
    fun placeAnchor(tapScreenX: Float, tapScreenY: Float, widthPx: Float, heightPx: Float): Boolean {
        // 如果 ARCore 可用且成功命中物理平面
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

                        _spatialAnchor.value = _spatialAnchor.value.copy(
                            trackerType = ARTrackerType.GOOGLE_ARCORE,
                            screenX = tapScreenX,
                            screenY = tapScreenY,
                            isAnchored = true,
                            planeTypeDescription = "物理桌面 (ARCore)"
                        )
                        _trackingStatus.value = ARTrackingStatus.ANCHORED
                        return true
                    }
                }
            }
        }

        // 通用传感器实景视差锚定 (零 GMS 依赖，100% 成功)
        universalTracker.anchorToDesktop(tapScreenX, tapScreenY)
        _spatialAnchor.value = _spatialAnchor.value.copy(
            trackerType = ARTrackerType.UNIVERSAL_SENSOR,
            screenX = tapScreenX,
            screenY = tapScreenY,
            isAnchored = true,
            distanceMeters = 0.65f,
            planeTypeDescription = "书桌实景 (零 GMS 通用视差)"
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
        universalTracker.updateScale(scaleMultiplier)
    }

    fun updateRotation(yawDeltaDegree: Float) {
        val current = _spatialAnchor.value.rotationYaw
        val next = (current + yawDeltaDegree) % 360f
        _spatialAnchor.value = _spatialAnchor.value.copy(rotationYaw = next)
        universalTracker.updateRotation(yawDeltaDegree)
    }

    fun resetAnchor() {
        anchor?.detach()
        anchor = null
        universalTracker.resetAnchor()
        _spatialAnchor.value = ARSpatialAnchor(
            trackerType = if (isARCoreAvailable) ARTrackerType.GOOGLE_ARCORE else ARTrackerType.UNIVERSAL_SENSOR,
            screenX = 0.5f,
            screenY = 0.65f,
            distanceMeters = 0.65f,
            scaleFactor = 1.0f,
            rotationYaw = 0f,
            isAnchored = false,
            planeTypeDescription = if (isARCoreAvailable) "ARCore 待放置" else "书桌实景 (零 GMS)"
        )
        _trackingStatus.value = ARTrackingStatus.PLANE_DETECTED
    }

    fun destroy() {
        universalTracker.stop()
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
