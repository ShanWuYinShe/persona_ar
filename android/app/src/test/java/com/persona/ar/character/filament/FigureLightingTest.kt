package com.persona.ar.character.filament

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureLightingTest {

    @Test
    fun `compute2BandSH returns 12 coefficients`() {
        val sh = FigureLighting.compute2BandSH()
        assertEquals(FigureLighting.SH_2BAND_COEFFICIENT_COUNT, sh.size)
        // L00 基础项应为正值 (全向漫反射)
        assertTrue("L00 R 应 > 0", sh[0] > 0f)
        assertTrue("L00 G 应 > 0", sh[1] > 0f)
        assertTrue("L00 B 应 > 0", sh[2] > 0f)
    }

    @Test
    fun `compute2BandSH responds to color temperature`() {
        val warmSH = FigureLighting.compute2BandSH(ambientColorTemp = -1f)
        val coolSH = FigureLighting.compute2BandSH(ambientColorTemp = 1f)

        // L1,-1 垂直偶极子 (索引 3..5: R, G, B)
        // 暖环境 (傍晚/暖灯) 下 R 显著增强，冷环境 (阴天/冷光) 下 B 显著增强
        assertTrue("暖环境下 L1,-1 R 应高于冷环境", warmSH[3] > coolSH[3])
        assertTrue("冷环境下 L1,-1 B 应高于暖环境", coolSH[5] > warmSH[5])
    }

    @Test
    fun `compute2BandSH scales with ambient tints`() {
        val baseSH = FigureLighting.compute2BandSH(tintRed = 1f, tintGreen = 1f, tintBlue = 1f)
        val tintedSH = FigureLighting.compute2BandSH(tintRed = 1.3f, tintGreen = 0.8f, tintBlue = 0.9f)

        // L00 与定向分量均应随通道增益线性响应
        assertEquals(baseSH[0] * 1.3f, tintedSH[0], 1e-4f)
        assertEquals(baseSH[1] * 0.8f, tintedSH[1], 1e-4f)
        assertEquals(baseSH[2] * 0.9f, tintedSH[2], 1e-4f)
    }

    @Test
    fun `resolvePbrProfile differentiates figure materials`() {
        // 头发：半光泽 PVC 漆面 (高光流动)
        val hair = FigureLighting.resolvePbrProfile("F00_000_Hair_00_HAIR_01")
        assertFalse(hair.isUnlit)
        assertEquals(0.38f, hair.roughness, 1e-3f)
        assertEquals(0.02f, hair.metallic, 1e-3f)

        // 皮肤：细腻磨砂树脂 (温润柔和)
        val skin = FigureLighting.resolvePbrProfile("F00_002_02_Body_00_SKIN")
        assertFalse(skin.isUnlit)
        assertEquals(0.68f, skin.roughness, 1e-3f)
        assertEquals(0.0f, skin.metallic, 1e-3f)

        // 鞋子：注塑成型高光小皮鞋 (扎实反光)
        val shoes = FigureLighting.resolvePbrProfile("F00_002_01_Shoes_01_CLOTH")
        assertFalse(shoes.isUnlit)
        assertEquals(0.28f, shoes.roughness, 1e-3f)
        assertEquals(0.08f, shoes.metallic, 1e-3f)

        // 常服：微纤维漫反射织物
        val cloth = FigureLighting.resolvePbrProfile("F00_002_01_Onepice_01_CLOTH")
        assertFalse(cloth.isUnlit)
        assertEquals(0.82f, cloth.roughness, 1e-3f)

        // 唇口：微润半透
        val mouth = FigureLighting.resolvePbrProfile("F00_000_00_FaceMouth_00_FACE")
        assertFalse(mouth.isUnlit)
        assertEquals(0.48f, mouth.roughness, 1e-3f)

        // 眼睛/睫毛：保持 unlit 锐利二次元表现
        val iris = FigureLighting.resolvePbrProfile("F00_000_00_EyeIris_00_EYE")
        assertTrue(iris.isUnlit)

        val eyelash = FigureLighting.resolvePbrProfile("F00_000_00_FaceEyelash_00_FACE")
        assertTrue(eyelash.isUnlit)

        val eyeline = FigureLighting.resolvePbrProfile("F00_000_00_FaceEyeline_00_FACE")
        assertTrue(eyeline.isUnlit)
    }

    @Test
    fun `compute2BandSH clamps extreme values safely`() {
        val sh = FigureLighting.compute2BandSH(ambientColorTemp = -5f, tintRed = -1f, tintGreen = 10f, tintBlue = 0f)
        assertEquals(FigureLighting.SH_2BAND_COEFFICIENT_COUNT, sh.size)
        for (coef in sh) {
            assertFalse("系数不应为 NaN 或无穷大", coef.isNaN() || coef.isInfinite())
        }
        // 红通道增益 clamp 到了 0.2f
        assertEquals(0.2f, sh[0], 1e-4f)
    }

    @Test
    fun `compute2BandSHFromProbe captures top-bottom and left-right color contrast`() {
        val sh = FigureLighting.compute2BandSHFromProbe(
            ambientColorTemp = 0.3f,
            tintRed = 1.0f,
            tintGreen = 1.0f,
            tintBlue = 1.0f,
            topR = 1.2f, topG = 1.15f, topB = 0.9f,
            bottomR = 0.55f, bottomG = 0.35f, bottomB = 0.25f,
            leftR = 0.7f, leftG = 0.9f, leftB = 1.2f,
            rightR = 1.25f, rightG = 0.85f, rightB = 0.65f
        )
        assertEquals(FigureLighting.SH_2BAND_COEFFICIENT_COUNT, sh.size)
        // 右侧比左侧更红 -> L1,+1 R > 0；左侧比右侧更蓝 -> L1,+1 B < 0
        assertTrue("右暖左冷时 X 偶极子红分量应为正，实际 ${sh[9]}", sh[9] > 0.05f)
        assertTrue("右暖左冷时 X 偶极子蓝分量应为负，实际 ${sh[11]}", sh[11] < -0.05f)
        // 顶部显著比底部亮 -> L1,-1 Y 偶极子为正
        assertTrue("顶亮底暗时 Y 偶极子应为正", sh[3] > 0.1f && sh[4] > 0.1f)
    }

    @Test
    fun `fillEnvironmentCubemapFace populates opaque valid RGBA pixels across faces and mips`() {
        val size = 8
        val bytes = ByteArray(size * size * 4)
        val top = floatArrayOf(1.2f, 1.1f, 0.95f)
        val bottom = floatArrayOf(0.5f, 0.4f, 0.35f)
        val left = floatArrayOf(0.8f, 0.9f, 1.1f)
        val right = floatArrayOf(1.1f, 0.9f, 0.75f)
        val global = floatArrayOf(0.95f, 0.95f, 0.95f)

        for (face in 0 until 6) {
            for (mip in 0..4) {
                FigureLighting.fillEnvironmentCubemapFace(
                    face = face, size = size, roughnessLevel = mip,
                    topRgb = top, bottomRgb = bottom, leftRgb = left, rightRgb = right, globalRgb = global,
                    outBytes = bytes
                )
                for (i in 0 until size * size) {
                    val a = bytes[i * 4 + 3].toInt() and 0xFF
                    assertEquals("Cubemap alpha 必须为 255", 255, a)
                }
            }
        }
        // 验证 +Y(面2, 天顶) 平均红通道亮度高于 -Y(面3, 桌面底部)
        FigureLighting.fillEnvironmentCubemapFace(2, size, 0, top, bottom, left, right, global, bytes)
        val topCenterR = bytes[(size * size / 2) * 4].toInt() and 0xFF
        FigureLighting.fillEnvironmentCubemapFace(3, size, 0, top, bottom, left, right, global, bytes)
        val bottomCenterR = bytes[(size * size / 2) * 4].toInt() and 0xFF
        assertTrue("天顶面($topCenterR)应亮于底面($bottomCenterR)", topCenterR > bottomCenterR)
    }
}
