package com.persona.ar.character.filament

import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import android.util.Log
import com.google.android.filament.utils.Utils

/**
 * 二次元卡通着色 (Toon Cel-Shading) 参数规范 —— 预留给后续在 PBR 管线之上叠加自定义 Toon 材质（matc 编译 .filamat）。
 * 当前手办走 gltfio 内置 Ubershader PBR 受光，已满足「真实体积感 + 迎光面高光随光源流动」。
 */
data class FilamentToonConfig(
    /** 卡通分阶阶梯明暗阈值数组 (Base / Shadow1 / Shadow2) */
    val celShadingSteps: FloatArray = floatArrayOf(0.32f, 0.68f),
    /** 边缘外扩描边宽度 (Inverted Hull Outline) */
    val outlineWidth: Float = 1.6f,
    /** 描边颜色 (深蓝紫高档二游线稿) */
    val outlineColor: Color = Color(0xFF28243D),
    /** 头发天使光环各向异性高光强度 (Anisotropic Specular Highlight) */
    val hairAnisotropy: Float = 0.85f,
    /** 皮肤次表面散射透光 (Subsurface Scattering 透光绯红) */
    val sssColor: Color = Color(0xFFFFB3BA),
    val sssStrength: Float = 0.45f,
    /** 边缘轮廓背光 (Rim Light Power) */
    val rimPower: Float = 3.2f,
    /** 接触环境光遮蔽强度 (Contact AO) */
    val contactAOIntensity: Float = 0.95f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FilamentToonConfig
        if (!celShadingSteps.contentEquals(other.celShadingSteps)) return false
        if (outlineWidth != other.outlineWidth) return false
        if (outlineColor != other.outlineColor) return false
        return true
    }

    override fun hashCode(): Int {
        var result = celShadingSteps.contentHashCode()
        result = 31 * result + outlineWidth.hashCode()
        result = 31 * result + outlineColor.hashCode()
        return result
    }
}

/**
 * 渲染一帧所需的全部输入：从 CharacterVisualState + ARSpatialAnchor 提炼出的纯数据快照。
 * 在 Compose 侧随状态变化构造，渲染循环（主线程 Choreographer）每帧只读取最新值，
 * 避免渲染回调直接持有 Compose 状态对象或 Big Data Class。
 */
data class FigureFrameState(
    /** 是否已锚定在桌面（false 时手办隐藏、仅清屏） */
    val anchored: Boolean = false,
    /** 落脚点在视口内的归一化坐标 (0..1，左上为原点) */
    val screenX: Float = 0.5f,
    val screenY: Float = 0.65f,
    /** 相机到落脚点的物理距离 (米) */
    val distanceMeters: Float = 0.65f,
    /** 手办缩放 (双指手势 0.4..2.2) */
    val scale: Float = 1f,
    /** 朝向偏航角 (度，0 为正面朝向相机) */
    val yawDegrees: Float = 0f,
    /** 相机俯仰角：0° 平视、90° 垂直俯视桌面 (来自 IMU) */
    val cameraPitchDegrees: Float = 45f,
    /** 相机横滚角 (度，来自 IMU) */
    val cameraRollDegrees: Float = 0f,
    /** 环境照度 (0.2 昏暗 ~ 1.35 明亮)，决定曝光与各光源强度 */
    val envLuminance: Float = 0.8f,
    val isLowLight: Boolean = false,
    /** 环境光 RGB 反射增益与色温偏向 (-1 冷蓝 ~ +1 暖黄) */
    val ambientTintRed: Float = 1f,
    val ambientTintGreen: Float = 1f,
    val ambientTintBlue: Float = 1f,
    val ambientColorTemp: Float = 0f,
    /** 主光源二维方向 (X: -1 左 .. +1 右；Y: -1 上 .. +1 下) 与定向强度 (0 全向漫射 ~ 1 强直射) */
    val lightDirectionX: Float = 0f,
    val lightDirectionY: Float = -0.5f,
    val lightDirectionalStrength: Float = 0.2f,
    /** 桌面支撑置信度 (1 坚实桌面 ~ 0 悬空) 与悬空离地比例，影响接触阴影强度 */
    val surfaceSupportConfidence: Float = 1f,
    val dangleAirDistance: Float = 0f,
    /** 帧时间 (纳秒)，驱动呼吸 / 待机骨骼动画 */
    val timeNanos: Long = 0L
)

/**
 * 进程级 Filament Engine 持有者：原生库初始化一次、Engine 创建一次，所有 FigureScene 实例共享。
 * Engine 创建伴随 GL/Vulkan 后端初始化（数十毫秒），且上下文为进程级资源，不应随页面重建。
 */
object FilamentEngineHolder {
    private const val TAG = "FilamentEngineHolder"

    @Volatile
    private var engineInstance: Engine? = null

    /** 获取（首次调用时创建）共享 Engine。线程安全。 */
    fun engine(): Engine = engineInstance ?: synchronized(this) {
        engineInstance ?: run {
            // 加载 filament-jni / gltfio-jni / filament-utils-jni 并完成原生层初始化（仅首次）
            Utils.init()
            Engine.create().also { engineInstance = it }
        }
    }

    /** Engine 是否已就绪（未就绪时调用方应走 2.5D Canvas 回退） */
    val isReady: Boolean get() = engineInstance != null

    /** 仅在进程退出前的全局清理路径调用；普通页面销毁不要调用（Engine 为共享资源） */
    fun release() {
        synchronized(this) {
            engineInstance?.let { runCatching { it.destroy() }.onFailure { e -> Log.w(TAG, "engine destroy: ${e.message}") } }
            engineInstance = null
        }
    }
}
