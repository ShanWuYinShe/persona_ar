package com.persona.ar.character.filament

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.utils.Utils
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart

/**
 * 渲染一帧所需的全部输入：从 CharacterVisualState + ARSpatialAnchor 提炼出的纯数据快照。
 */
data class FigureFrameState(
    val anchored: Boolean = false,
    val screenX: Float = 0.5f,
    val screenY: Float = 0.65f,
    val distanceMeters: Float = 0.65f,
    val scale: Float = 1f,
    val yawDegrees: Float = 0f,
    val cameraPitchDegrees: Float = 45f,
    val cameraRollDegrees: Float = 0f,
    val envLuminance: Float = 0.8f,
    val isLowLight: Boolean = false,
    val ambientTintRed: Float = 1f,
    val ambientTintGreen: Float = 1f,
    val ambientTintBlue: Float = 1f,
    val ambientColorTemp: Float = 0f,
    /** 6 方向环境光色调（顶/底/左/右），驱动真实 IBL 与反射贴图 */
    val envTopRed: Float = 1f,
    val envTopGreen: Float = 1f,
    val envTopBlue: Float = 1f,
    val envBottomRed: Float = 1f,
    val envBottomGreen: Float = 0.96f,
    val envBottomBlue: Float = 0.92f,
    val envLeftRed: Float = 1f,
    val envLeftGreen: Float = 1f,
    val envLeftBlue: Float = 1f,
    val envRightRed: Float = 1f,
    val envRightGreen: Float = 1f,
    val envRightBlue: Float = 1f,
    /** 局部桌面底色反射 */
    val localSurfaceTintRed: Float = 1f,
    val localSurfaceTintGreen: Float = 1f,
    val localSurfaceTintBlue: Float = 1f,
    val lightDirectionX: Float = 0f,
    val lightDirectionY: Float = -0.5f,
    val lightDirectionalStrength: Float = 0.25f,
    val hasWorldLightDir: Boolean = false,
    val worldLightDirX: Float = 0.25f,
    val worldLightDirY: Float = -0.85f,
    val worldLightDirZ: Float = 0.45f,
    val action: ActionType = ActionType.IDLE,
    val actionStartedAtNanos: Long = 0L,
    val emotion: EmotionType = EmotionType.NEUTRAL,
    val expression: String = "SMILE",
    val stance: SkeletonStance = SkeletonStance.STANDING,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val inertialSwayAngle: Float = 0f,
    val touchReaction: TouchPart? = null,
    val touchReactionStartedAtNanos: Long = 0L,
    val lipSyncAmplitude: Float = 0f,
    /** 真实 3D 物理空间锚定与 6DoF 相机位姿 */
    val isWorldAnchored: Boolean = false,
    val worldAnchorX: Float = 0f,
    val worldAnchorY: Float = -0.12f,
    val worldAnchorZ: Float = -0.65f,
    val cameraPosX: Float = 0f,
    val cameraPosY: Float = 0f,
    val cameraPosZ: Float = 0f,
    val cameraRightX: Float = 1f,
    val cameraRightY: Float = 0f,
    val cameraRightZ: Float = 0f,
    val cameraForwardX: Float = 0f,
    val cameraForwardY: Float = 0f,
    val cameraForwardZ: Float = -1f,
    val cameraUpX: Float = 0f,
    val cameraUpY: Float = 1f,
    val cameraUpZ: Float = 0f,
    val effectiveVerticalFovDegrees: Float = 62f,
    val isInView: Boolean = true,
    val azimuthDeltaDegrees: Float = 0f,
    val timeNanos: Long = 0L
)

/**
 * 进程级 Filament Engine 持有者：原生库初始化一次、Engine 创建一次，所有 FigureScene 实例共享。
 */
object FilamentEngineHolder {
    private const val TAG = "FilamentEngineHolder"

    @Volatile
    private var engineInstance: Engine? = null

    fun engine(): Engine = engineInstance ?: synchronized(this) {
        engineInstance ?: run {
            Utils.init()
            Engine.create().also { engineInstance = it }
        }
    }

    val isReady: Boolean get() = engineInstance != null

    fun release() {
        synchronized(this) {
            engineInstance?.let { runCatching { it.destroy() }.onFailure { e -> Log.w(TAG, "engine destroy: ${e.message}") } }
            engineInstance = null
        }
    }
}
