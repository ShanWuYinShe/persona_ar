package com.persona.ar.character.animation

/**
 * 人形骨骼姿态求解器：
 * 1. 把逐骨骼的欧拉角增量（度）转成局部旋转，重组 local TRS；
 * 2. 按拓扑序做正向运动学（FK），得到全部节点的模型空间世界矩阵；
 * 3. 按 gltfio Animator 的同款公式计算蒙皮矩阵并供上层上传：
 *    boneMatrix = inverse(renderableWorld) · jointWorld · inverseBindMatrix。
 *
 * 全部为纯 Kotlin（不依赖 android.opengl / Filament），可在 JVM 单测中直接验证。
 */
class PoseSolver(val rig: GltfRig) {

    /** 每节点局部旋转四元数 (x,y,z,w) */
    val localRot = FloatArray(rig.nodeCount * 4)
    /** 每节点模型空间世界矩阵（列主序 16 float） */
    val world = FloatArray(rig.nodeCount * 16)

    private val authored = FloatArray(4)
    private val composed = FloatArray(4)
    private val localMatrix = FloatArray(16)
    private val renderableInverse = HashMap<Int, FloatArray>()
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)

    private val hipsNode = rig.humanoid[HumanoidBone.HIPS] ?: -1

    init {
        // 静止世界矩阵：既用于推导 renderable 逆变换，也作为坐标基准
        for (i in 0 until rig.nodeCount * 4) {
            localRot[i] = if (i % 4 == 3) 1f else 0f
        }
        solve(FloatArray(rig.nodeCount * 3), 0f, 0f, 0f)
        for (m in rig.meshNodes) {
            val inv = FloatArray(16)
            Mat4.invertRigid(inv, world.copyOfRange(m.nodeIndex * 16, m.nodeIndex * 16 + 16))
            renderableInverse[m.nodeIndex] = inv
        }
    }

    /**
     * 求解全身姿态。
     * @param angles 每节点 3 个角度（rx, ry, rz，单位度；欧拉顺序 先 Z 再 Y 最后 X），长度 = nodeCount*3
     * @param hipsOffsetX/Y/Z 骨盆（HIPS 节点）在父空间中的附加平移（米），用于跳跃/坐下
     */
    fun solve(angles: FloatArray, hipsOffsetX: Float, hipsOffsetY: Float, hipsOffsetZ: Float) {
        for (idx in rig.order) {
            val i3 = idx * 3
            val rx = angles[i3]
            val ry = angles[i3 + 1]
            val rz = angles[i3 + 2]
            val hasAuthored = rx != 0f || ry != 0f || rz != 0f
            val i4 = idx * 4
            // local = restR * authored（后乘：动作轴随骨骼父链旋转，符合关节铰链语义）
            if (hasAuthored) {
                QuatMath.fromEulerDegrees(authored, rx, ry, rz)
                QuatMath.multiply(composed, 0, rig.restR, i4, authored, 0)
                QuatMath.copy(localRot, i4, composed, 0)
            } else {
                QuatMath.copy(localRot, i4, rig.restR, i4)
            }
            val isHips = idx == hipsNode
            Mat4.fromTRS(
                localMatrix,
                rig.restT[i3] + if (isHips) hipsOffsetX else 0f,
                rig.restT[i3 + 1] + if (isHips) hipsOffsetY else 0f,
                rig.restT[i3 + 2] + if (isHips) hipsOffsetZ else 0f,
                localRot, i4,
                rig.restS[i3], rig.restS[i3 + 1], rig.restS[i3 + 2]
            )
            val p = rig.parent[idx]
            if (p < 0) {
                System.arraycopy(localMatrix, 0, world, idx * 16, 16)
            } else {
                Mat4.multiply(world, idx * 16, world, p * 16, localMatrix, 0)
            }
        }
    }

    /**
     * 计算某蒙皮网格节点的骨骼矩阵（长度 joints*16，列主序），直接传给
     * `RenderableManager.setBonesAsMatrices`。
     */
    fun computeSkinMatrices(mesh: GltfRig.MeshNode, out: FloatArray) {
        val skin = rig.skins[mesh.skinIndex]
        val invRenderable = renderableInverse[mesh.nodeIndex] ?: return
        val joints = skin.joints
        for (j in joints.indices) {
            val jointNode = joints[j]
            // tmpA = inverse(renderableWorld) * jointWorld
            Mat4.multiply(tmpA, 0, invRenderable, 0, world, jointNode * 16)
            // out = tmpA * inverseBind
            Mat4.multiply(out, j * 16, tmpA, 0, skin.inverseBind, j * 16)
        }
    }

    /** 世界矩阵中的位置（调试/投影用） */
    fun worldPosition(node: Int, out: FloatArray) {
        out[0] = world[node * 16 + 12]
        out[1] = world[node * 16 + 13]
        out[2] = world[node * 16 + 14]
    }
}