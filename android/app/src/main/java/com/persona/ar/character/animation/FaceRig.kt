package com.persona.ar.character.animation

import com.persona.ar.data.model.EmotionType
import kotlin.math.exp
import kotlin.math.min

/**
 * VRM 表情（morph target）驱动器。
 *
 * 源模型是 VRoid 导出，Face 网格带 41 个 blend shape：ALL_/BRW_/EYE_/MTH_ 四层组合出
 * Joy / Fun / Angry / Sorrow / Surprised 等表情，另有 MTH_A/I/U/E/O 元音口型与 EYE_Close 眨眼。
 * 升级：支持表情预设权重的时间平滑过渡（不再一帧硬切表情），保持眨眼与说话口型的瞬态灵动感。
 */
class FaceRig(morphTargetNames: Array<String>) {

    private companion object {
        val MOUTH_KEYS = listOf(
            "MTH_A", "MTH_I", "MTH_U", "MTH_E", "MTH_O",
            "MTH_Neutral", "MTH_Joy", "MTH_Fun", "MTH_Angry", "MTH_Sorrow", "MTH_Surprised",
            "MTH_Up", "MTH_Down"
        )
        const val EXPR_SMOOTH_RATE = 11.5f
    }

    val morphCount: Int = morphTargetNames.size
    private val index = HashMap<String, Int>(morphCount * 2)
    private val mouthIndices = IntArray(MOUTH_KEYS.size) { -1 }
    private val presets = HashMap<String, FloatArray>()
    private val smoothedBaseWeights = FloatArray(morphCount)
    private var hasSmoothedInit = false
    private var mthA = -1
    private var eyeClose = -1
    private var eyeExtraOn = -1

    init {
        for ((i, name) in morphTargetNames.withIndex()) {
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
        presets["JOY"] = buildPreset(
            "BRW_Joy" to 0.75f, "EYE_Joy" to 0.90f, "MTH_Joy" to 0.72f, "HA_Fung1" to 0.60f
        )
        presets["SMILE"] = buildPreset(
            "BRW_Joy" to 0.42f, "EYE_Joy" to 0.35f, "MTH_Up" to 0.55f, "MTH_Fun" to 0.38f
        )
        presets["WINK"] = buildPreset(
            "EYE_Close_R" to 1.0f, "EYE_Joy_L" to 0.78f, "BRW_Fun" to 0.60f, "MTH_Fun" to 0.65f, "HA_Fung1" to 0.75f
        )
        presets["BLUSH_FACE"] = buildPreset(
            "BRW_Sorrow" to 0.72f, "EYE_Joy" to 0.55f, "EYE_Close" to 0.22f, "MTH_Fun" to 0.42f, "MTH_Up" to 0.24f
        )
        presets["CRY"] = buildPreset(
            "BRW_Sorrow" to 0.90f, "EYE_Sorrow" to 0.82f, "MTH_Sorrow" to 0.68f, "MTH_Down" to 0.32f
        )
        presets["CURIOUS"] = buildPreset(
            "BRW_Surprised" to 0.65f, "EYE_Surprised" to 0.36f, "MTH_O" to 0.22f
        )
        presets["POUT"] = buildPreset(
            "BRW_Angry" to 0.90f, "EYE_Angry" to 0.68f, "MTH_U" to 0.78f, "MTH_Up" to 0.32f
        )
        presets["SURPRISED"] = buildPreset(
            "BRW_Surprised" to 0.85f, "EYE_Surprised" to 0.82f, "MTH_O" to 0.48f
        )
        presets["SLEEPY"] = buildPreset(
            "BRW_Sorrow" to 0.45f, "EYE_Close" to 0.72f, "MTH_Neutral" to 0.35f, "MTH_Down" to 0.18f
        )
        presets["SMUG"] = buildPreset(
            "BRW_Fun" to 0.85f, "EYE_Joy" to 0.28f, "EYE_Angry" to 0.25f,
            "MTH_Fun" to 0.78f, "MTH_Up" to 0.28f, "HA_Fung1" to 0.95f
        )
        presets["NEUTRAL"] = buildPreset("EYE_Natural" to 0.20f, "MTH_Neutral" to 0.40f)
        presets["ANGRY"] = presets["POUT"]!!
        presets["SAD"] = presets["CRY"]!!
        presets["SHY"] = presets["BLUSH_FACE"]!!
        presets["CONFIDENT"] = presets["SMUG"]!!
        presets["THINKING"] = presets["CURIOUS"]!!
    }

    private fun buildPreset(vararg pairs: Pair<String, Float>): FloatArray {
        val arr = FloatArray(morphCount)
        for ((key, value) in pairs) {
            val idx = index[key] ?: continue
            arr[idx] = value.coerceIn(0f, 1f)
        }
        return arr
    }

    /** 直接填充表情权重（无跨帧延迟，适用于单元测试或初始化帧） */
    fun fillWeights(
        out: FloatArray,
        expression: String,
        emotion: EmotionType,
        eyesClosed: Float,
        mouthOpen: Float
    ) {
        fillWeightsSmooth(out, expression, emotion, eyesClosed, mouthOpen, dtSeconds = 10f)
    }

    /**
     * 平滑填充一帧 morph 权重：基础表情按时间指数顺滑过渡，眨眼与口型即时同步响应。
     */
    fun fillWeightsSmooth(
        out: FloatArray,
        expression: String,
        emotion: EmotionType,
        eyesClosed: Float,
        mouthOpen: Float,
        dtSeconds: Float
    ) {
        val preset = presets[expression] ?: presets[emotion.name] ?: presets["SMILE"]!!
        val count = min(preset.size, out.size)
        if (!hasSmoothedInit || dtSeconds >= 1.0f) {
            java.util.Arrays.fill(smoothedBaseWeights, 0f)
            System.arraycopy(preset, 0, smoothedBaseWeights, 0, min(preset.size, smoothedBaseWeights.size))
            hasSmoothedInit = true
        } else {
            val k = (1f - exp(-EXPR_SMOOTH_RATE * dtSeconds.coerceIn(0f, 0.2f))).coerceIn(0f, 1f)
            for (i in 0 until min(preset.size, smoothedBaseWeights.size)) {
                smoothedBaseWeights[i] += (preset[i] - smoothedBaseWeights[i]) * k
            }
        }

        java.util.Arrays.fill(out, 0f)
        System.arraycopy(smoothedBaseWeights, 0, out, 0, count)

        val mouth = mouthOpen.coerceIn(0f, 1f)
        if (mouth > 0.01f) {
            for (mi in mouthIndices) {
                if (mi in 0 until out.size) out[mi] *= (1f - mouth * 0.72f)
            }
        }
        if (mthA in 0 until out.size) out[mthA] = maxOf(out[mthA], mouth * 0.92f)

        val close = eyesClosed.coerceIn(0f, 1f)
        if (eyeClose in 0 until out.size) out[eyeClose] = maxOf(out[eyeClose], close)
        if (eyeExtraOn in 0 until out.size) out[eyeExtraOn] = 0f
        for (i in out.indices) out[i] = out[i].coerceIn(0f, 1f)
    }
}
