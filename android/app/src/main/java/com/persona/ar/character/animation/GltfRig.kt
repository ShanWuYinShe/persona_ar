package com.persona.ar.character.animation

/**
 * 解析自 GLB 的运行时骨骼 / 蒙皮拓扑数据（纯数据结构，不含 Android 依赖）。
 *
 * 关节节点变换全部按 glTF 的 local TRS 存储；FK 由 [PoseSolver] 完成，
 * 蒙皮矩阵由 Filament 1.81 的 `RenderableManager.setBonesAsMatrices` 直接上传
 * （该版本 gltfio 不再从 TransformManager 自动推导骨骼矩阵，必须显式上传）。
 */
class GltfRig(
    val nodeCount: Int,
    /** 节点名（glTF node.name） */
    val names: Array<String>,
    /** 父节点索引，-1 表示根 */
    val parent: IntArray,
    /** 父节点在前的拓扑遍历顺序 */
    val order: IntArray,
    /** 静止局部平移，3 个 float / 节点 */
    val restT: FloatArray,
    /** 静止局部旋转四元数 (x,y,z,w)，4 个 float / 节点 */
    val restR: FloatArray,
    /** 静止局部缩放，3 个 float / 节点 */
    val restS: FloatArray,
    /** 人形骨骼语义 → 节点索引 */
    val humanoid: Map<HumanoidBone, Int>,
    /** glTF skins（关节顺序 + inverseBindMatrices） */
    val skins: Array<Skin>,
    /** 带蒙皮的网格节点 */
    val meshNodes: Array<MeshNode>,
    /** 手指关节（待机/动作时统一做自然弯曲，避免 T-pose 手指张开） */
    val fingerJoints: Array<FingerJoint>,
    /** 头发关节链（从发根到发梢），用于惯性甩发次级动力学 */
    val hairChains: Array<IntArray>,
    /** 裙摆前/后/侧链（每链按根到梢排序），用于在坐姿/跳跃/鞠躬时反向补偿防穿模 */
    val skirtFrontChains: Array<IntArray> = emptyArray(),
    val skirtBackChains: Array<IntArray> = emptyArray(),
    val skirtSideChains: Array<IntArray> = emptyArray()
) {
    /** 单个 glTF skin：joints 为节点索引数组；inverseBind 为 16 个 float / 关节（列主序） */
    class Skin(val joints: IntArray, val inverseBind: FloatArray)

    /** 网格节点 → 使用哪个 skin */
    class MeshNode(val nodeIndex: Int, val skinIndex: Int, val name: String)

    /** 手指关节 + 相对弯曲角度（度数；正负由左右手在解析时标定） */
    class FingerJoint(val nodeIndex: Int, val curlDegrees: Float)
}

/** 人形骨骼语义枚举（与 VRM humanoid 一一对应） */
enum class HumanoidBone {
    HIPS, SPINE, CHEST, UPPER_CHEST, NECK, HEAD,
    SHOULDER_L, UPPER_ARM_L, LOWER_ARM_L, HAND_L,
    SHOULDER_R, UPPER_ARM_R, LOWER_ARM_R, HAND_R,
    UPPER_LEG_L, LOWER_LEG_L, FOOT_L,
    UPPER_LEG_R, LOWER_LEG_R, FOOT_R
}