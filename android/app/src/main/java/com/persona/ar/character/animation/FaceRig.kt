package com.persona.ar.character.animation

import com.persona.ar.data.model.EmotionType

/**
 * VRM 表情（morph target）驱动器。
 *
 * 源模型是 VRoid 导出，Face 网格带 41 个 blend shape：ALL_/BRW_/EYE_/MTH_ 四层组合出
 * Joy / Fun / Angry / Sorrow / Surprised 等表情，另有 MTH_A/I/U/E/O 元音口型与 EYE_Close 眨眼。
 * 这里用「表情预设 + 眨眼 + 口型」三部分直接合成每帧 morph 权重，
 * 再交给 `RenderableManager.setMorphWeights` —— 这是让 3D 手办「活着」的关键之一。
 */
class FaceRig(morphTargetNames: Array<String>) {

    private companion object {
        val MOUTH_KEYS = listOf("MTH_A", "MTH_I", "MTH_U", "MTH_E", "MTH_O",
                "MTH_Neutral", "MTH_Joy", "MTH_Fun", "MTH_Angry", "MTH_Sorrow", "MTH_Surprised",
                "MTH_Up", "MTH_Down")
    }

    val morphCount: Int = morphTargetNames.size
    private val index = HashMap<String, Int>(morphCount * 2)
    private val mouthIndices = IntArray(MOUTH_KEYS.size) { -1 }
    private val presets = HashMap<String, FloatArray>()
    private var mthA = -1
    private var eyeClose = -1
    private var eyeExtraOn = -1

    init {
        for ((i, name) in morphTargetNames.withIndex()) {
            // matc 命名的 morph 形如 Face.M_F00_000_00_Fcl_ALL_Joy，用后缀精确匹配
            val key = name.substringAfterLast("Fcl_", name)
            index.putIfAbsent(key, i)
            index.putIfAbsent(name, i)
        }
        for ((k, key) in MOUTH_KEYS.withIndex()) mouthIndices[k] = index[key] ?: -1
        mthA = index["MTH_A"] ?: -1
        eyeClose = index["EYE_Close"] ?: -1
        eyeExtraOn = index["EyeExtra_On"] ?: index["EyeExtra.M_F00_000_00_EyeExtra_On"] ?: -1
        buildPresets()
    }

    private fun buildPresets() {
        // 开怀欢笑：飞扬眉毛 + 弯月笑眼 + 露齿大笑 + 可爱小虎牙
        presets["JOY"] = buildPreset(
            "ALL_Joy" to 0.95f, "BRW_Joy" to 0.75f, "EYE_Joy" to 0.95f, "MTH_Joy" to 0.85f, "HA_Fung1" to 0.70f
        )
        // 治愈微笑：柔和明媚笑颜
        presets["SMILE"] = buildPreset(
            "ALL_Joy" to 0.65f, "BRW_Joy" to 0.45f, "EYE_Joy" to 0.65f, "MTH_Joy" to 0.50f, "MTH_Neutral" to 0.15f
        )
        // 俏皮眨眼：单眼完全闭合 + 另一眼弯笑 + 调皮小虎牙
        presets["WINK"] = buildPreset(
            "EYE_Close_R" to 1.0f, "EYE_Joy" to 0.85f, "ALL_Joy" to 0.65f, "BRW_Joy" to 0.55f, "MTH_Fun" to 0.70f, "HA_Fung1" to 0.85f
        )
        // 害羞娇颤：八字怜弱眉 + 半阖笑眼 + 羞涩小抿嘴
        presets["BLUSH_FACE"] = buildPreset(
            "ALL_Joy" to 0.45f, "BRW_Sorrow" to 0.85f, "EYE_Joy" to 0.65f, "EYE_Close" to 0.35f, "MTH_Fun" to 0.45f, "MTH_I" to 0.30f
        )
        // 委屈难过：垂眉 + 嘴角下撇哭脸
        presets["CRY"] = buildPreset(
            "ALL_Sorrow" to 0.90f, "BRW_Sorrow" to 0.95f, "EYE_Sorrow" to 0.75f, "MTH_Sorrow" to 0.80f, "MTH_Down" to 0.50f
        )
        // 好奇探究：挑眉 + 微张小圆嘴
        presets["CURIOUS"] = buildPreset(
            "ALL_Surprised" to 0.35f, "BRW_Surprised" to 0.75f, "EYE_Surprised" to 0.45f, "MTH_Neutral" to 0.30f, "MTH_O" to 0.25f
        )
        // 经典傲娇鼓颊：气呼呼倒竖眉 + 瞪视 + 经典小鸭子嘟嘴 (MTH_U)
        presets["POUT"] = buildPreset(
            "ALL_Angry" to 0.85f, "BRW_Angry" to 0.95f, "EYE_Angry" to 0.65f, "MTH_Angry" to 0.75f, "MTH_U" to 0.85f
        )
        // 惊惶瞪大：圆滚滚眼瞳 + 张大嘴巴
        presets["SURPRISED"] = buildPreset(
            "ALL_Surprised" to 0.95f, "BRW_Surprised" to 1.0f, "EYE_Surprised" to 1.0f, "MTH_Surprised" to 0.85f, "MTH_O" to 0.75f
        )
        // 困意朦胧：眼皮重重垂落 + 困倦眉 + 微张打小哈欠
        presets["SLEEPY"] = buildPreset(
            "ALL_Sorrow" to 0.30f, "BRW_Sorrow" to 0.55f, "EYE_Close" to 0.75f, "MTH_O" to 0.40f, "MTH_Down" to 0.25f
        )
        // 嚣张得意：猫咪嘴微笑 + 经典自信小虎牙 (HA_Fung1 顶格)
        presets["SMUG"] = buildPreset(
            "ALL_Fun" to 0.90f, "BRW_Fun" to 0.85f, "EYE_Joy" to 0.80f, "MTH_Fun" to 0.85f, "HA_Fung1" to 1.0f
        )
        presets["NEUTRAL"] = buildPreset("MTH_Neutral" to 0.4f)
        presets["ANGRY"] = presets["POUT"]!!
        presets["SAD"] = presets["CRY"]!!
        presets["SHY"] = presets["BLUSH_FACE"]!!
        presets["CONFIDENT"] = presets["SMUG"]!!
    }

    private fun buildPreset(vararg pairs: Pair<String, Float>): FloatArray {
        val arr = FloatArray(morphCount)
        for ((key, value) in pairs) {
            val idx = index[key] ?: continue
            arr[idx] = value.coerceIn(0f, 1f)
        }
        return arr
    }

    /** 根据表情名 / 情绪 / 眨眼 / 口型，填充一帧 morph 权重 */
    fun fillWeights(out: FloatArray, expression: String, emotion: EmotionType,
                    eyesClosed: Float, mouthOpen: Float) {
        java.util.Arrays.fill(out, 0f)
        val preset = presets[expression] ?: presets[emotion.name] ?: presets["SMILE"]!!
        System.arraycopy(preset, 0, out, 0, minOf(preset.size, out.size))

        // 说话时压低嘴部表情预设，避免与口型叠加后变成怪异咧嘴
        val mouth = mouthOpen.coerceIn(0f, 1f)
        if (mouth > 0.01f) {
            for (mi in mouthIndices) {
                if (mi >= 0) out[mi] *= (1f - mouth * 0.72f)
            }
        }
        if (mthA >= 0) out[mthA] = maxOf(out[mthA], mouth * 0.92f)

        // 眨眼：EYE_Close 同时闭合双眼；说话/惊讶时轻微睁大（EYE_Close 保持 0）
        val close = eyesClosed.coerceIn(0f, 1f)
        if (eyeClose >= 0) out[eyeClose] = maxOf(out[eyeClose], close)
        if (eyeExtraOn >= 0) out[eyeExtraOn] = 0f
        for (i in out.indices) out[i] = out[i].coerceIn(0f, 1f)
    }
}