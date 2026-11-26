package com.persona.ar.character.filament

import kotlin.math.abs

/**
 * 3D 手办高保真光照与材质参数模型：
 * 包含 2-Band 定向球谐环境光照（4 组基函数共 12 系数：全向 + 天地垂直 + 纵深 + 左右偶极子）
 * 与二次元手办（PVC/树脂/漆面）PBR 材质剖面。
 */
object FigureLighting {
    /** 2-Band 球谐系数总长度 = 4 组基函数 × 3 通道 (RGB) = 12 */
    const val SH_2BAND_COEFFICIENT_COUNT = 12

    /**
     * 兼容基础签名：根据现实环境色温与 RGB 色渗解算 2-Band 球谐系数。
     */
    fun compute2BandSH(
        ambientColorTemp: Float = 0f,
        tintRed: Float = 1f,
        tintGreen: Float = 1f,
        tintBlue: Float = 1f
    ): FloatArray {
        val temp = ambientColorTemp.coerceIn(-1f, 1f)
        val tr = tintRed.coerceIn(0.2f, 1.5f)
        val tg = tintGreen.coerceIn(0.2f, 1.5f)
        val tb = tintBlue.coerceIn(0.2f, 1.5f)

        val l00R = 1.0f * tr
        val l00G = 1.0f * tg
        val l00B = 1.0f * tb

        val l1yR = (0.24f - 0.05f * temp) * tr
        val l1yG = 0.20f * tg
        val l1yB = (0.16f + 0.05f * temp) * tb

        val l1zR = 0.04f * tr
        val l1zG = 0.04f * tg
        val l1zB = 0.05f * tb

        val l1xR = 0.02f * tr
        val l1xG = 0.02f * tg
        val l1xB = 0.02f * tb

        return floatArrayOf(
            l00R, l00G, l00B,
            l1yR, l1yG, l1yB,
            l1zR, l1zG, l1zB,
            l1xR, l1xG, l1xB
        )
    }

    /**
     * 结合相机实测的「天顶 / 桌面 / 左 / 右」四方环境色温与桌面反弹色渗，解算真实定向 2-Band 球谐光照。
     * 让手办顶部受天光色、底部受真实桌面色渗、左右侧受窗户或台灯环境色温自然着色。
     */
    fun compute2BandSHFromProbe(
        ambientColorTemp: Float,
        tintRed: Float,
        tintGreen: Float,
        tintBlue: Float,
        topR: Float,
        topG: Float,
        topB: Float,
        bottomR: Float,
        bottomG: Float,
        bottomB: Float,
        leftR: Float,
        leftG: Float,
        leftB: Float,
        rightR: Float,
        rightG: Float,
        rightB: Float
    ): FloatArray {
        val temp = ambientColorTemp.coerceIn(-1f, 1f)
        val tr = tintRed.coerceIn(0.3f, 1.45f)
        val tg = tintGreen.coerceIn(0.3f, 1.45f)
        val tb = tintBlue.coerceIn(0.3f, 1.45f)

        // L00 全向漫反射均值（融合全局光与局部反弹）
        val l00R = (tr * 0.65f + (topR + bottomR) * 0.175f).coerceIn(0.3f, 1.45f)
        val l00G = (tg * 0.65f + (topG + bottomG) * 0.175f).coerceIn(0.3f, 1.45f)
        val l00B = (tb * 0.65f + (topB + bottomB) * 0.175f).coerceIn(0.3f, 1.45f)

        // L1,-1 (Y 轴偶极子)：天顶光与桌面底面色渗之差 + 物理垂直明暗反差
        val l1yR = ((topR - bottomR) * 0.34f + (0.24f - 0.04f * temp) * tr).coerceIn(-0.45f, 0.55f)
        val l1yG = ((topG - bottomG) * 0.34f + 0.22f * tg).coerceIn(-0.45f, 0.55f)
        val l1yB = ((topB - bottomB) * 0.34f + (0.20f + 0.05f * temp) * tb).coerceIn(-0.45f, 0.55f)

        // L1,0 (Z 轴纵深微渐变)
        val l1zR = 0.05f * tr
        val l1zG = 0.05f * tg
        val l1zB = 0.06f * tb

        // L1,1 (X 轴左右偶极子)：真实左右侧光色差
        val l1xR = ((rightR - leftR) * 0.32f).coerceIn(-0.4f, 0.4f)
        val l1xG = ((rightG - leftG) * 0.32f).coerceIn(-0.4f, 0.4f)
        val l1xB = ((rightB - leftB) * 0.32f).coerceIn(-0.4f, 0.4f)

        return floatArrayOf(
            l00R, l00G, l00B,
            l1yR, l1yG, l1yB,
            l1zR, l1zG, l1zB,
            l1xR, l1xG, l1xB
        )
    }

    /** 6 面环境反射 Cubemap（每面 16x16 RGBA8）填充工具，按 5 级粗糙度 mipmap 生成柔和环境高光反射 */
    fun fillEnvironmentCubemapFace(
        face: Int,
        size: Int,
        roughnessLevel: Int,
        topRgb: FloatArray,
        bottomRgb: FloatArray,
        leftRgb: FloatArray,
        rightRgb: FloatArray,
        globalRgb: FloatArray,
        outBytes: ByteArray
    ) {
        // 粗糙度越高 (level 0..4)，越趋向于平均环境漫反射色，避免金属/头发高光过噪
        val blur = (roughnessLevel / 4f).coerceIn(0f, 1f)
        var ptr = 0
        for (py in 0 until size) {
            val v = (py + 0.5f) / size * 2f - 1f
            for (px in 0 until size) {
                val u = (px + 0.5f) / size * 2f - 1f
                // Filament Cubemap 方向映射: +X(0), -X(1), +Y(2), -Y(3), +Z(4), -Z(5)
                val dx: Float
                val dy: Float
                val dz: Float
                when (face) {
                    0 -> { dx = 1f; dy = -v; dz = -u }
                    1 -> { dx = -1f; dy = -v; dz = u }
                    2 -> { dx = u; dy = 1f; dz = v }
                    3 -> { dx = u; dy = -1f; dz = -v }
                    4 -> { dx = u; dy = -v; dz = 1f }
                    else -> { dx = -u; dy = -v; dz = -1f }
                }
                val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
                val nx = dx / len
                val ny = dy / len

                // 垂直半球混合（顶光略亮 + 桌面偏柔和暖/实底色）
                val wyTop = ((ny + 1f) * 0.5f).coerceIn(0f, 1f)
                val wxRight = ((nx + 1f) * 0.5f).coerceIn(0f, 1f)

                val vertR = bottomRgb[0] * (1f - wyTop) + topRgb[0] * wyTop
                val vertG = bottomRgb[1] * (1f - wyTop) + topRgb[1] * wyTop
                val vertB = bottomRgb[2] * (1f - wyTop) + topRgb[2] * wyTop

                val horizR = leftRgb[0] * (1f - wxRight) + rightRgb[0] * wxRight
                val horizG = leftRgb[1] * (1f - wxRight) + rightRgb[1] * wxRight
                val horizB = leftRgb[2] * (1f - wxRight) + rightRgb[2] * wxRight

                // 顶部半球提亮，模拟真实室内灯/天光高光反射带
                val skyHighlight = if (ny > 0.25f) 1f + (ny - 0.25f) * 0.45f * (1f - blur * 0.6f) else (0.78f + 0.22f * wyTop)

                val sharpR = (vertR * 0.65f + horizR * 0.35f) * skyHighlight
                val sharpG = (vertG * 0.65f + horizG * 0.35f) * skyHighlight
                val sharpB = (vertB * 0.65f + horizB * 0.35f) * skyHighlight

                val rr = (sharpR * (1f - blur * 0.65f) + globalRgb[0] * (blur * 0.65f)).coerceIn(0f, 1.5f)
                val gg = (sharpG * (1f - blur * 0.65f) + globalRgb[1] * (blur * 0.65f)).coerceIn(0f, 1.5f)
                val bb = (sharpB * (1f - blur * 0.65f) + globalRgb[2] * (blur * 0.65f)).coerceIn(0f, 1.5f)

                outBytes[ptr++] = ((rr / 1.5f).coerceIn(0f, 1f) * 255f).toInt().toByte()
                outBytes[ptr++] = ((gg / 1.5f).coerceIn(0f, 1f) * 255f).toInt().toByte()
                outBytes[ptr++] = ((bb / 1.5f).coerceIn(0f, 1f) * 255f).toInt().toByte()
                outBytes[ptr++] = 255.toByte()
            }
        }
    }

    /** 检查两个环境数组是否有显著变化（用于节流重建 IBL） */
    fun hasSignificantShift(a: FloatArray, b: FloatArray, threshold: Float = 0.035f): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) {
            if (abs(a[i] - b[i]) > threshold) return true
        }
        return false
    }

    data class PbrProfile(
        val roughness: Float,
        val metallic: Float,
        val isUnlit: Boolean
    )

    fun resolvePbrProfile(materialName: String): PbrProfile {
        val upper = materialName.uppercase()
        val isEye = "EYE" in upper || "EYELASH" in upper || "EYELINE" in upper
        return when {
            isEye -> PbrProfile(roughness = 0.9f, metallic = 0.0f, isUnlit = true)
            "HAIR" in upper -> PbrProfile(roughness = 0.38f, metallic = 0.02f, isUnlit = false)
            "SKIN" in upper -> PbrProfile(roughness = 0.68f, metallic = 0.0f, isUnlit = false)
            "SHOES" in upper -> PbrProfile(roughness = 0.28f, metallic = 0.08f, isUnlit = false)
            "CLOTH" in upper || "ONEPICE" in upper -> PbrProfile(roughness = 0.82f, metallic = 0.0f, isUnlit = false)
            "MOUTH" in upper -> PbrProfile(roughness = 0.48f, metallic = 0.0f, isUnlit = false)
            else -> PbrProfile(roughness = 0.70f, metallic = 0.0f, isUnlit = false)
        }
    }
}
