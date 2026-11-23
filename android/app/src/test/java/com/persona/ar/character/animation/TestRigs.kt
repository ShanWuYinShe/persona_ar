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