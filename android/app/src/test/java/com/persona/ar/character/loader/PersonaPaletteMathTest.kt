package com.persona.ar.character.loader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class PersonaPaletteMathTest {

    @Test
    fun parseHexRgbSupportsSixAndEightDigitHexAndFallback() {
        val six = PersonaPaletteMath.parseHexRgb("#FF2E63")
        assertEquals(1f, six[0], 0.005f)
        assertEquals(0x2E / 255f, six[1], 0.005f)
        assertEquals(0x63 / 255f, six[2], 0.005f)

        val eight = PersonaPaletteMath.parseHexRgb("#FF7B61FF")
        assertEquals(0x7B / 255f, eight[0], 0.005f)
        assertEquals(0x61 / 255f, eight[1], 0.005f)
        assertEquals(1f, eight[2], 0.005f)

        val fallback = PersonaPaletteMath.parseHexRgb("invalid", floatArrayOf(0.2f, 0.3f, 0.4f))
        assertArrayEquals(floatArrayOf(0.2f, 0.3f, 0.4f), fallback, 1e-5f)
    }

    @Test
    fun linearConversionFollowsSrgbCurve() {
        assertEquals(0f, PersonaPaletteMath.srgbToLinear(0f), 1e-5f)
        assertEquals(1f, PersonaPaletteMath.srgbToLinear(1f), 1e-5f)
        val mid = PersonaPaletteMath.srgbToLinear(0.5f)
        assertTrue("linear(0.5) should be ~0.214, got $mid", mid in 0.21f..0.22f)

        val factor = PersonaPaletteMath.toLinearBaseColorFactor("#FFFFFF")
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), factor, 1e-5f)
    }

    @Test
    fun recolorPreservesTransparencyNeutralHighlightsAndDistinguishesPersonas() {
        val transparent = 0x00123456
        val pureWhiteCollar = (0xFF shl 24) or (245 shl 16) or (246 shl 8) or 248
        val cyanCloth = (0xFF shl 24) or (50 shl 16) or (140 shl 8) or 190
        val brightTrim = (0xFF shl 24) or (60 shl 16) or (225 shl 8) or 240

        val pixelsLuna = intArrayOf(transparent, pureWhiteCollar, cyanCloth, brightTrim)
        PersonaPaletteMath.recolorArgbPixels(
            pixels = pixelsLuna,
            primaryHex = "#1E1A29",
            accentHex = "#FF5277",
            preserveNeutralHighlights = true
        )

        // 透明与纯白领口保持原样
        assertEquals(transparent, pixelsLuna[0])
        assertEquals(pureWhiteCollar, pixelsLuna[1])

        // 亮色强调饰边倾向于粉色强调色（R 明显高于 G/B）
        val trimR = (pixelsLuna[3] ushr 16) and 0xFF
        val trimG = (pixelsLuna[3] ushr 8) and 0xFF
        val trimB = pixelsLuna[3] and 0xFF
        assertTrue("Luna trim should be reddish ($trimR,$trimG,$trimB)", trimR > trimG && trimR > trimB)

        val pixelsAiri = intArrayOf(transparent, pureWhiteCollar, cyanCloth, brightTrim)
        PersonaPaletteMath.recolorArgbPixels(
            pixels = pixelsAiri,
            primaryHex = "#FFF3E0",
            accentHex = "#00E676",
            preserveNeutralHighlights = true
        )
        val airiTrimR = (pixelsAiri[3] ushr 16) and 0xFF
        val airiTrimG = (pixelsAiri[3] ushr 8) and 0xFF
        assertTrue("Airi trim should be green ($airiTrimR,$airiTrimG)", airiTrimG > airiTrimR)

        // coolTonesOnly=true 必须完整保护暖调肤色并重着色冷调丝袜/袖套
        val peachSkin = (0xFF shl 24) or (239 shl 16) or (190 shl 8) or 170
        val tealStocking = (0xFF shl 24) or (60 shl 16) or (210 shl 8) or 230
        val bodyPixels = intArrayOf(peachSkin, tealStocking)
        PersonaPaletteMath.recolorArgbPixels(
            pixels = bodyPixels,
            primaryHex = "#1E1A29",
            accentHex = "#FF5277",
            preserveNeutralHighlights = true,
            coolTonesOnly = true
        )
        assertEquals(peachSkin, bodyPixels[0])
        val sockR = (bodyPixels[1] ushr 16) and 0xFF
        val sockB = bodyPixels[1] and 0xFF
        assertTrue("Stocking highlight should shift to pink-crimson ($sockR vs $sockB)", sockR > sockB)
    }

    @Test
    fun patchGlbRebuildsAlignedBinaryAndUpdatesMaterialsAndTextures() {
        val synthetic = buildSyntheticGlb(
            imageBytes = byteArrayOf(1, 2, 3, 4, 5),
            meshTailBytes = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        )
        assertNotNull(PersonaPaletteMath.parseGlb(synthetic))
        assertNull(PersonaPaletteMath.parseGlb(byteArrayOf(0, 1, 2)))

        val patched = PersonaPaletteMath.patchGlb(
            originalGlb = synthetic,
            materialBaseColorOverrides = mapOf(
                "HairMat" to floatArrayOf(0.9f, 0.91f, 0.94f, 1f)
            ),
            imageByteOverrides = mapOf(
                "IrisTex" to byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 10, 11, 12, 13)
            )
        )
        assertNotNull(patched)
        assertEquals(0, patched!!.size % 4)

        val chunks = PersonaPaletteMath.parseGlb(patched)!!
        val imgs = PersonaPaletteMath.extractImageBytesByName(chunks)
        assertArrayEquals(
            byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 10, 11, 12, 13),
            imgs["IrisTex"]
        )

        // 验证紧随其后的 mesh bufferView 偏移同步对齐后数据未损坏
        val bvs = chunks.root.getAsJsonArray("bufferViews")
        val meshBv = bvs[1].asJsonObject
        val meshOff = meshBv.get("byteOffset").asInt
        val meshLen = meshBv.get("byteLength").asInt
        assertEquals(0, meshOff % 4)
        assertEquals(8, meshLen)
        val actualMesh = chunks.bin.copyOfRange(meshOff, meshOff + meshLen)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80), actualMesh)

        // 验证材质 baseColorFactor 更新正确
        val mats = chunks.root.getAsJsonArray("materials")
        val bcf = mats[0].asJsonObject.getAsJsonObject("pbrMetallicRoughness").getAsJsonArray("baseColorFactor")
        assertEquals(0.9f, bcf[0].asFloat, 1e-4f)
        assertEquals(0.94f, bcf[2].asFloat, 1e-4f)

        // 确认 buffers[0].byteLength 与 node 结构保留完整
        assertEquals(chunks.bin.size, chunks.root.getAsJsonArray("buffers")[0].asJsonObject.get("byteLength").asInt)
        assertEquals("Root", chunks.root.getAsJsonArray("nodes")[0].asJsonObject.get("name").asString)
    }

    private fun buildSyntheticGlb(imageBytes: ByteArray, meshTailBytes: ByteArray): ByteArray {
        val binOut = ByteArrayOutputStream()
        val imgOff = 0
        binOut.write(imageBytes)
        while (binOut.size() % 4 != 0) binOut.write(0)
        val meshOff = binOut.size()
        binOut.write(meshTailBytes)
        while (binOut.size() % 4 != 0) binOut.write(0)
        val binBytes = binOut.toByteArray()

        val root = JsonObject().apply {
            add("asset", JsonObject().apply { addProperty("version", "2.0") })
            add("buffers", JsonArray().apply {
                add(JsonObject().apply { addProperty("byteLength", binBytes.size) })
            })
            add("bufferViews", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("buffer", 0)
                    addProperty("byteOffset", imgOff)
                    addProperty("byteLength", imageBytes.size)
                })
                add(JsonObject().apply {
                    addProperty("buffer", 0)
                    addProperty("byteOffset", meshOff)
                    addProperty("byteLength", meshTailBytes.size)
                })
            })
            add("images", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "IrisTex")
                    addProperty("mimeType", "image/png")
                    addProperty("bufferView", 0)
                })
            })
            add("materials", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "HairMat")
                    add("pbrMetallicRoughness", JsonObject().apply {
                        add("baseColorFactor", JsonArray().apply {
                            add(1f); add(1f); add(1f); add(1f)
                        })
                    })
                })
            })
            add("nodes", JsonArray().apply {
                add(JsonObject().apply { addProperty("name", "Root") })
            })
        }

        val jsonRaw = root.toString().toByteArray(StandardCharsets.UTF_8)
        val jsonPad = (4 - (jsonRaw.size % 4)) % 4
        val totalLen = 12 + 8 + jsonRaw.size + jsonPad + 8 + binBytes.size
        val bb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67)
        bb.putInt(2)
        bb.putInt(totalLen)
        bb.putInt(jsonRaw.size + jsonPad)
        bb.putInt(0x4E4F534A)
        bb.put(jsonRaw)
        repeat(jsonPad) { bb.put(0x20.toByte()) }
        bb.putInt(binBytes.size)
        bb.putInt(0x004E4942)
        bb.put(binBytes)
        return bb.array()
    }
}
