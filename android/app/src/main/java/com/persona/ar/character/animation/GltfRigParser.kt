package com.persona.ar.character.animation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * GLB 解析器：从手工转换后的 VRM GLB 中读出节点层级、静止 TRS、skins 与 inverseBindMatrices。
 *
 * 为什么需要自行解析：Filament 1.81 的 gltfio Java API 不暴露关节顺序 / inverseBindMatrices / 节点父子关系，
 * 而 1.81 起蒙皮骨骼矩阵必须由应用侧计算并 `setBonesAsMatrices` 上传，因此需要完整的骨骼拓扑。
 * 解析只在模型安装时执行一次（IO 线程），不参与每帧渲染。
 */
object GltfRigParser {
    private const val TAG = "GltfRigParser"
    private const val MAGIC_GLTF = 0x46546C67 // 'glTF'
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    /** VRM humanoid 骨骼名 → 语义；按优先级排列，兼容 VRM 0.x/1.0 与常见 Mixamo 命名 */
    private val humanoidAliases: List<Pair<HumanoidBone, List<String>>> = listOf(
        HumanoidBone.HIPS to listOf("J_Bip_C_Hips", "Hips", "mixamorig:Hips"),
        HumanoidBone.SPINE to listOf("J_Bip_C_Spine", "Spine", "mixamorig:Spine"),
        HumanoidBone.CHEST to listOf("J_Bip_C_Chest", "Chest", "mixamorig:Spine1"),
        HumanoidBone.UPPER_CHEST to listOf("J_Bip_C_UpperChest", "UpperChest", "mixamorig:Spine2"),
        HumanoidBone.NECK to listOf("J_Bip_C_Neck", "Neck", "mixamorig:Neck"),
        HumanoidBone.HEAD to listOf("J_Bip_C_Head", "Head", "mixamorig:Head"),
        HumanoidBone.SHOULDER_L to listOf("J_Bip_L_Shoulder", "LeftShoulder", "mixamorig:LeftShoulder"),
        HumanoidBone.UPPER_ARM_L to listOf("J_Bip_L_UpperArm", "LeftUpperArm", "mixamorig:LeftArm"),
        HumanoidBone.LOWER_ARM_L to listOf("J_Bip_L_LowerArm", "LeftLowerArm", "mixamorig:LeftForeArm"),
        HumanoidBone.HAND_L to listOf("J_Bip_L_Hand", "LeftHand", "mixamorig:LeftHand"),
        HumanoidBone.SHOULDER_R to listOf("J_Bip_R_Shoulder", "RightShoulder", "mixamorig:RightShoulder"),
        HumanoidBone.UPPER_ARM_R to listOf("J_Bip_R_UpperArm", "RightUpperArm", "mixamorig:RightArm"),
        HumanoidBone.LOWER_ARM_R to listOf("J_Bip_R_LowerArm", "RightLowerArm", "mixamorig:RightForeArm"),
        HumanoidBone.HAND_R to listOf("J_Bip_R_Hand", "RightHand", "mixamorig:RightHand"),
        HumanoidBone.UPPER_LEG_L to listOf("J_Bip_L_UpperLeg", "LeftUpperLeg", "mixamorig:LeftUpLeg"),
        HumanoidBone.LOWER_LEG_L to listOf("J_Bip_L_LowerLeg", "LeftLowerLeg", "mixamorig:LeftLeg"),
        HumanoidBone.FOOT_L to listOf("J_Bip_L_Foot", "LeftFoot", "mixamorig:LeftFoot"),
        HumanoidBone.UPPER_LEG_R to listOf("J_Bip_R_UpperLeg", "RightUpperLeg", "mixamorig:RightUpLeg"),
        HumanoidBone.LOWER_LEG_R to listOf("J_Bip_R_LowerLeg", "RightLowerLeg", "mixamorig:RightLeg"),
        HumanoidBone.FOOT_R to listOf("J_Bip_R_Foot", "RightFoot", "mixamorig:RightFoot")
    )

    fun parse(bytes: ByteArray): GltfRig? {
        return try {
            doParse(bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "GLB 骨骼解析失败", t)
            null
        }
    }

    private fun doParse(bytes: ByteArray): GltfRig? {
        if (bytes.size < 20) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != MAGIC_GLTF) { Log.e(TAG, "非 GLB 文件"); return null }
        var offset = 12
        var json: JSONObject? = null
        var binOffset = -1
        var binLength = 0
        val total = buf.getInt(8)
        while (offset + 8 <= total && offset + 8 <= bytes.size) {
            val chunkLength = buf.getInt(offset)
            val chunkType = buf.getInt(offset + 4)
            val dataOffset = offset + 8
            if (chunkType == CHUNK_JSON) {
                val text = String(bytes, dataOffset, chunkLength, Charsets.UTF_8)
                json = JSONObject(text)
            } else if (chunkType == CHUNK_BIN) {
                binOffset = dataOffset
                binLength = chunkLength
            }
            offset = dataOffset + chunkLength
        }
        val root = json ?: return null
        if (binOffset < 0) { Log.e(TAG, "GLB 缺少 BIN chunk"); return null }
        val bin = ByteBuffer.wrap(bytes, binOffset, binLength).order(ByteOrder.LITTLE_ENDIAN).slice()
        bin.order(ByteOrder.LITTLE_ENDIAN)

        val nodesJson = root.getJSONArray("nodes")
        val nodeCount = nodesJson.length()
        val names = Array(nodeCount) { "" }
        val parent = IntArray(nodeCount) { -1 }
        val restT = FloatArray(nodeCount * 3)
        val restR = FloatArray(nodeCount * 4)
        val restS = FloatArray(nodeCount * 3)
        val children = ArrayList<MutableList<Int>>(nodeCount)
        for (i in 0 until nodeCount) children.add(mutableListOf())

        for (i in 0 until nodeCount) {
            val node = nodesJson.getJSONObject(i)
            names[i] = node.optString("name", "node_$i")
            if (node.has("matrix")) {
                val arr = node.getJSONArray("matrix")
                val m = FloatArray(16) { arr.getDouble(it).toFloat() }
                // 列主序 4x4 分解：平移取第 4 列；缩放取各列模长；旋转取归一化 3x3
                restT[i * 3] = m[12]; restT[i * 3 + 1] = m[13]; restT[i * 3 + 2] = m[14]
                val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
                val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
                val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
                restS[i * 3] = sx; restS[i * 3 + 1] = sy; restS[i * 3 + 2] = sz
                val r = FloatArray(9)
                if (sx > 1e-8f) { r[0] = m[0] / sx; r[1] = m[1] / sx; r[2] = m[2] / sx }
                if (sy > 1e-8f) { r[3] = m[4] / sy; r[4] = m[5] / sy; r[5] = m[6] / sy }
                if (sz > 1e-8f) { r[6] = m[8] / sz; r[7] = m[9] / sz; r[8] = m[10] / sz }
                quatFromRotationMatrix(r, restR, i * 4)
            } else {
                val t = node.optJSONArray("translation")
                restT[i * 3] = t?.let { it.getDouble(0).toFloat() } ?: 0f
                restT[i * 3 + 1] = t?.let { it.getDouble(1).toFloat() } ?: 0f
                restT[i * 3 + 2] = t?.let { it.getDouble(2).toFloat() } ?: 0f
                val r = node.optJSONArray("rotation")
                if (r != null) {
                    restR[i * 4] = r.getDouble(0).toFloat()
                    restR[i * 4 + 1] = r.getDouble(1).toFloat()
                    restR[i * 4 + 2] = r.getDouble(2).toFloat()
                    restR[i * 4 + 3] = r.getDouble(3).toFloat()
                } else {
                    restR[i * 4] = 0f; restR[i * 4 + 1] = 0f; restR[i * 4 + 2] = 0f; restR[i * 4 + 3] = 1f
                }
                val s = node.optJSONArray("scale")
                restS[i * 3] = s?.let { it.getDouble(0).toFloat() } ?: 1f
                restS[i * 3 + 1] = s?.let { it.getDouble(1).toFloat() } ?: 1f
                restS[i * 3 + 2] = s?.let { it.getDouble(2).toFloat() } ?: 1f
            }
            val ch = node.optJSONArray("children")
            if (ch != null) {
                for (c in 0 until ch.length()) {
                    val child = ch.getInt(c)
                    children[i].add(child)
                    parent[child] = i
                }
            }
        }

        // 拓扑序：从所有根节点 BFS，保证父节点先于子节点被求解
        val order = IntArray(nodeCount)
        var orderSize = 0
        val queue = ArrayDeque<Int>()
        for (i in 0 until nodeCount) if (parent[i] < 0) queue.add(i)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order[orderSize++] = n
            for (c in children[n]) queue.add(c)
        }
        if (orderSize != nodeCount) {
            // 兜底：存在环或孤立节点时按索引顺序求解（本资产不会发生）
            for (i in 0 until nodeCount) if (i >= orderSize) order[i] = i
        }

        val nameToIndex = HashMap<String, Int>(nodeCount * 2)
        for (i in 0 until nodeCount) nameToIndex.putIfAbsent(names[i], i)

        // skins
        val skinsJson = root.optJSONArray("skins")
        val skins = ArrayList<GltfRig.Skin>()
        if (skinsJson != null) {
            for (si in 0 until skinsJson.length()) {
                val skin = skinsJson.getJSONObject(si)
                val jointsArr = skin.getJSONArray("joints")
                val joints = IntArray(jointsArr.length()) { jointsArr.getInt(it) }
                val ibmAccessor = skin.optInt("inverseBindMatrices", -1)
                val ibm = if (ibmAccessor >= 0) readMat4Accessor(root, bin, ibmAccessor, joints.size) else FloatArray(joints.size * 16)
                skins.add(GltfRig.Skin(joints, ibm))
            }
        }

        // 网格节点（node.mesh + node.skin）
        val meshNodes = ArrayList<GltfRig.MeshNode>()
        for (i in 0 until nodeCount) {
            val node = nodesJson.getJSONObject(i)
            if (node.has("mesh") && node.has("skin")) {
                meshNodes.add(GltfRig.MeshNode(i, node.getInt("skin"), names[i]))
            }
        }
        if (meshNodes.isEmpty() || skins.isEmpty()) {
            Log.w(TAG, "未找到蒙皮网格（meshNodes=${meshNodes.size} skins=${skins.size}），该模型不做骨骼动画")
        }

        // 人形骨骼映射
        val humanoid = LinkedHashMap<HumanoidBone, Int>()
        for ((bone, aliases) in humanoidAliases) {
            for (alias in aliases) {
                val idx = nameToIndex[alias]
                if (idx != null) { humanoid[bone] = idx; break }
            }
        }

        // 手指关节：VRM 命名 J_Bip_{L,R}_{Index,Middle,Ring,Little,Thumb}{1,2,3}
        val fingerJoints = ArrayList<GltfRig.FingerJoint>()
        val fingerNames = listOf("Index", "Middle", "Ring", "Little", "Thumb")
        for (side in listOf("L", "R")) {
            val sign = if (side == "L") 1f else -1f
            for ((fi, fname) in fingerNames.withIndex()) {
                for (seg in 1..3) {
                    val idx = nameToIndex["J_Bip_${side}_${fname}$seg"] ?: continue
                    // 拇指活动方向不同：绕掌面法线做小角度内收；其余手指朝掌心弯曲
                    val curl = when {
                        fname == "Thumb" -> 8f * (if (fi == 4) 1f else 1f)
                        seg == 1 -> 14f
                        seg == 2 -> 24f
                        else -> 16f
                    }
                    fingerJoints.add(GltfRig.FingerJoint(idx, curl * sign))
                }
            }
        }

        // 头发关节链：从 Head 的直接子节点中寻找 HairJoint* 起始链，沿单子节点向下遍历
        val hairChains = ArrayList<IntArray>()
        val headIdx = humanoid[HumanoidBone.HEAD]
        if (headIdx != null) {
            for (child in children[headIdx]) {
                if (!names[child].startsWith("HairJoint")) continue
                val chain = ArrayList<Int>()
                var cur = child
                var guard = 0
                while (guard++ < 8) {
                    chain.add(cur)
                    val next = children[cur]
                    if (next.size != 1) break
                    cur = next[0]
                }
                if (chain.size >= 2) hairChains.add(chain.toIntArray())
            }
        }

        // 裙摆骨骼链（VRoid 常见命名：J_Sec_{L,R}_Skirt{Front,Back,Side}1 -> 2）
        val skirtFrontChains = extractNamedChains(names, children, listOf("J_Sec_L_SkirtFront1", "J_Sec_R_SkirtFront1"))
        val skirtBackChains = extractNamedChains(names, children, listOf("J_Sec_L_SkirtBack1", "J_Sec_R_SkirtBack1"))
        val skirtSideChains = extractNamedChains(names, children, listOf("J_Sec_L_SkirtSide1", "J_Sec_R_SkirtSide1"))

        Log.i(TAG, "rig parsed: nodes=$nodeCount skins=${skins.size} meshNodes=${meshNodes.size} " +
                "humanoid=${humanoid.size}/${HumanoidBone.values().size} fingers=${fingerJoints.size} " +
                "hairChains=${hairChains.size} skirt=${skirtFrontChains.size}/${skirtBackChains.size}/${skirtSideChains.size}")
        return GltfRig(
            nodeCount = nodeCount,
            names = names,
            parent = parent,
            order = order,
            restT = restT,
            restR = restR,
            restS = restS,
            humanoid = humanoid,
            skins = skins.toTypedArray(),
            meshNodes = meshNodes.toTypedArray(),
            fingerJoints = fingerJoints.toTypedArray(),
            hairChains = hairChains.toTypedArray(),
            skirtFrontChains = skirtFrontChains,
            skirtBackChains = skirtBackChains,
            skirtSideChains = skirtSideChains
        )
    }

    private fun extractNamedChains(names: Array<String>, children: List<List<Int>>, roots: List<String>): Array<IntArray> {
        val out = ArrayList<IntArray>(roots.size)
        for (rootName in roots) {
            val start = names.indexOfFirst { it == rootName || it.endsWith("_$rootName") }
            if (start < 0) continue
            val chain = ArrayList<Int>(3)
            var cur = start
            var guard = 0
            while (guard++ < 4) {
                chain.add(cur)
                val next = children[cur].firstOrNull { !names[it].endsWith("_end") } ?: break
                cur = next
            }
            if (chain.isNotEmpty()) out.add(chain.toIntArray())
        }
        return out.toTypedArray()
    }

    private fun readMat4Accessor(root: JSONObject, bin: ByteBuffer, accessorIndex: Int, count: Int): FloatArray {
        val accessors = root.getJSONArray("accessors")
        val acc = accessors.getJSONObject(accessorIndex)
        val componentType = acc.getInt("componentType")
        val type = acc.getString("type")
        require(componentType == 5126 && type == "MAT4") { "inverseBindMatrices 必须为 float MAT4" }
        val out = FloatArray(count * 16)
        val view = root.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val viewOffset = view.optInt("byteOffset", 0)
        val accessorOffset = acc.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 64)
        val base = bin.position() + viewOffset + accessorOffset
        for (i in 0 until count) {
            for (k in 0 until 16) {
                out[i * 16 + k] = bin.getFloat(base + i * stride + k * 4)
            }
        }
        return out
    }

    /** 3x3 旋转矩阵（列主序 r[col*3+row]）→ 四元数 (x,y,z,w) */
    private fun quatFromRotationMatrix(r: FloatArray, out: FloatArray, outOffset: Int) {
        val r00 = r[0]; val r10 = r[1]; val r20 = r[2]
        val r01 = r[3]; val r11 = r[4]; val r21 = r[5]
        val r02 = r[6]; val r12 = r[7]; val r22 = r[8]
        val trace = r00 + r11 + r22
        var x: Float; var y: Float; var z: Float; var w: Float
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            w = 0.25f * s; x = (r21 - r12) / s; y = (r02 - r20) / s; z = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            w = (r21 - r12) / s; x = 0.25f * s; y = (r01 + r10) / s; z = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            w = (r02 - r20) / s; x = (r01 + r10) / s; y = 0.25f * s; z = (r12 + r21) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            w = (r10 - r01) / s; x = (r02 + r20) / s; y = (r12 + r21) / s; z = 0.25f * s
        }
        out[outOffset] = x; out[outOffset + 1] = y; out[outOffset + 2] = z; out[outOffset + 3] = w
    }
}