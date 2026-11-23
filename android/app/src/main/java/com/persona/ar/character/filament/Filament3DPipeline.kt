package com.persona.ar.character.filament

import androidx.compose.ui.graphics.Color
import com.persona.ar.character.skeleton.JointType
import com.persona.ar.character.skeleton.SkeletonJoint
import com.persona.ar.character.skeleton.Vector3D

/**
 * 100% 离线纯本地 Filament 3D PBR 与二次元卡通着色 (Toon Cel-Shading) 管线规范
 * 零 GMS 依赖，国内全设备离线即用
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
 * 3D 骨骼与 Filament / Canvas 统一渲染驱动桥接器
 */
class FilamentSkeletonBridge(
    val toonConfig: FilamentToonConfig = FilamentToonConfig()
) {
    private val boneMatrixBuffer = FloatArray(JointType.values().size * 16)
    var isNativeFilamentAvailable: Boolean = false
        private set

    init {
        // 检测系统是否具备 Filament 原生 JNI 库环境 (支持自动优雅降级到高精视差 Canvas 3D)
        isNativeFilamentAvailable = try {
            System.loadLibrary("filament-jni")
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 将 3D 骨骼关节变换同步至渲染管线
     */
    fun syncSkeletonBones(joints: Map<JointType, SkeletonJoint>) {
        joints.forEach { (type, joint) ->
            val idx = type.ordinal * 16
            val pos = joint.worldPos
            val rot = joint.localRot

            // 计算 4x4 变换矩阵填充到 Native Buffer
            boneMatrixBuffer[idx + 12] = pos.x
            boneMatrixBuffer[idx + 13] = pos.y
            boneMatrixBuffer[idx + 14] = pos.z
            boneMatrixBuffer[idx + 15] = 1.0f
        }
    }

    /**
     * 获取指定骨骼的世界坐标位置
     */
    fun getJointWorldPos(type: JointType, joints: Map<JointType, SkeletonJoint>): Vector3D {
        return joints[type]?.worldPos ?: Vector3D.ZERO
    }
}
