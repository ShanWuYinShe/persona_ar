package com.persona.ar.character.filament

/**
 * 3D 手办高保真光照与材质参数模型：
 * 包含 2-Band 球谐环境光照（4 组基函数共 12 系数）解算与二次元手办（PVC/树脂/漆面）PBR 材质剖面。
 */
object FigureLighting {
    /** 2-Band 球谐系数总长度 = 4 组基函数 × 3 通道 (RGB) = 12 */
    const val SH_2BAND_COEFFICIENT_COUNT = 12

    /**
     * 根据现实环境色温与 RGB 色渗解算 2-Band 球谐系数。
     *
     * 基函数定义（Ramamoorthi & Hanrahan 2001）：
     * - L00 (index 0..2)：全向漫反射常数项（均匀基础环境照度）；
     * - L1,-1 (index 3..5)：垂直 Y 轴偶极子（+Y 为天顶顶光偏冷/中性，-Y 为桌面底光偏暖地表色渗）；
     * - L1,0 (index 6..8)：前后 Z 轴微渐变；
     * - L1,1 (index 9..11)：左右 X 轴微平衡。
     *
     * @param ambientColorTemp -1.0f (暖光/傍晚) .. +1.0f (冷光/日光)
     * @param tintRed 环境红通道增益 (0.25 .. 1.35)
     * @param tintGreen 环境绿通道增益 (0.25 .. 1.35)
     * @param tintBlue 环境蓝通道增益 (0.25 .. 1.35)
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

        // L00: 漫反射基底，受环境色彩融合
        val l00R = 1.0f * tr
        val l00G = 1.0f * tg
        val l00B = 1.0f * tb

        // L1,-1: 垂直偶极子：天顶偏冷/中性，桌面底面反射随环境色温呈现冷暖反差
        // 暖色环境时桌面反射暖色更浓；冷色环境时顶光冷色更浓
        val l1yR = (0.24f - 0.05f * temp) * tr
        val l1yG = 0.20f * tg
        val l1yB = (0.16f + 0.05f * temp) * tb

        // L1,0: 纵深微渐变（手办背光与迎光微反差）
        val l1zR = 0.04f * tr
        val l1zG = 0.04f * tg
        val l1zB = 0.05f * tb

        // L1,1: 左右微平衡
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
     * 手办部位材质 PBR 目标参数：
     * 用于运行时材质校准或模型导出自检。
     */
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
