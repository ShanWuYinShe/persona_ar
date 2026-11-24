package com.persona.ar.character.animation

/** 测试用最小骨骼：20 个人形骨骼各占一个节点（无层级/蒙皮），只验证角度合成逻辑 */
object TestRigs {
    fun flatHumanoid(): GltfRig {
        val bones = HumanoidBone.values()
        val nodeCount = bones.size
        val names = Array(nodeCount) { bones[it].name }
        val parent = IntArray(nodeCount) { -1 }
        val order = IntArray(nodeCount) { it }
        val restT = FloatArray(nodeCount * 3)
        val restR = FloatArray(nodeCount * 4) { if (it % 4 == 3) 1f else 0f }
        val restS = FloatArray(nodeCount * 3) { 1f }
        val humanoid = bones.mapIndexed { i, b -> b to i }.toMap()
        return GltfRig(
            nodeCount = nodeCount,
            names = names,
            parent = parent,
            order = order,
            restT = restT,
            restR = restR,
            restS = restS,
            humanoid = humanoid,
            skins = emptyArray(),
            meshNodes = emptyArray(),
            fingerJoints = emptyArray(),
            hairChains = emptyArray()
        )
    }

    /**
     * 真实骨架缩样：root 下挂 Head 与左右手臂链（上臂 → 前臂 → 手），局部平移取自
     * `assets/models/hoshino/figure.glb` 的静止 TRS，静止旋转全为单位四元数。
     * 用于验证双手动作是否左右对称（只保留 FK 需要的最小节点，避免依赖完整 GLB）。
     */
    fun armRig(): GltfRig {
        val nodeCount = 8
        val restT = floatArrayOf(
            0f, 1.3f, 0f,                    // 0 root
            0f, 0.166f, 0f,                  // 1 head
            -0.0863f, -0.0149f, -0.0054f,    // 2 upperArmL
            -0.219f, -0.0102f, -0.0018f,     // 3 lowerArmL
            -0.2136f, -0.0005f, -0.0176f,    // 4 handL
            0.0863f, -0.0149f, -0.0054f,     // 5 upperArmR
            0.219f, -0.0102f, -0.0018f,      // 6 lowerArmR
            0.2136f, -0.0005f, -0.0176f      // 7 handR
        )
        return GltfRig(
            nodeCount = nodeCount,
            names = arrayOf("root", "head", "upperArmL", "lowerArmL", "handL", "upperArmR", "lowerArmR", "handR"),
            parent = intArrayOf(-1, 0, 0, 2, 3, 0, 5, 6),
            order = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            restT = restT,
            restR = FloatArray(nodeCount * 4) { if (it % 4 == 3) 1f else 0f },
            restS = FloatArray(nodeCount * 3) { 1f },
            humanoid = mapOf(
                HumanoidBone.HEAD to 1,
                HumanoidBone.UPPER_ARM_L to 2,
                HumanoidBone.LOWER_ARM_L to 3,
                HumanoidBone.HAND_L to 4,
                HumanoidBone.UPPER_ARM_R to 5,
                HumanoidBone.LOWER_ARM_R to 6,
                HumanoidBone.HAND_R to 7
            ),
            skins = emptyArray(),
            meshNodes = emptyArray(),
            fingerJoints = emptyArray(),
            hairChains = emptyArray()
        )
    }

    /** 三级链：root(0) → hips(1, y=1) → head(2, y=0.5)，用于 FK 世界坐标断言 */
    fun chainRig(): GltfRig {
        val nodeCount = 3
        val restT = floatArrayOf(
            0f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0.5f, 0f
        )
        val restR = FloatArray(nodeCount * 4) { if (it % 4 == 3) 1f else 0f }
        val restS = FloatArray(nodeCount * 3) { 1f }
        return GltfRig(
            nodeCount = nodeCount,
            names = arrayOf("root", "hips", "head"),
            parent = intArrayOf(-1, 0, 1),
            order = intArrayOf(0, 1, 2),
            restT = restT,
            restR = restR,
            restS = restS,
            humanoid = mapOf(HumanoidBone.HIPS to 1, HumanoidBone.HEAD to 2),
            skins = emptyArray(),
            meshNodes = emptyArray(),
            fingerJoints = emptyArray(),
            hairChains = emptyArray()
        )
    }
}
