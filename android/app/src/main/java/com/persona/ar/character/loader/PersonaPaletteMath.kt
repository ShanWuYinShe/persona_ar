package com.persona.ar.character.loader

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 纯 Kotlin 角色外观色板与 GLB 二进制材质/纹理重构核心，完全脱离 Android SDK 运行于 JVM 单测。
 */
object PersonaPaletteMath {

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942  // "BIN\u0000"

    data class PersonaAppearancePalette(
        val id: String,
        val hairHex: String,
        val eyeHex: String,
        val outfitHex: String,
        val accentHex: String
    )

    /**
     * 解析 `#RRGGBB` 或 `#AARRGGBB` 十六进制颜色为归一化 sRGB `[r, g, b]`。
     */
    fun parseHexRgb(hex: String, fallback: FloatArray = floatArrayOf(1f, 1f, 1f)): FloatArray {
        val clean = hex.trim().removePrefix("#")
        val rgbHex = when (clean.length) {
            6 -> clean
            8 -> clean.substring(2) // 忽略 Alpha 前缀
            else -> return fallback.copyOf()
        }
        val value = rgbHex.toIntOrNull(16) ?: return fallback.copyOf()
        val r = ((value shr 16) and 0xFF) / 255f
        val g = ((value shr 8) and 0xFF) / 255f
        val b = (value and 0xFF) / 255f
        return floatArrayOf(r, g, b)
    }

    /** IEC 61966-2-1 sRGB -> 线性颜色空间，供 glTF `baseColorFactor` 使用。 */
    fun srgbToLinear(c: Float): Float {
        val clamped = c.coerceIn(0f, 1f)
        return if (clamped <= 0.04045f) {
            clamped / 12.92f
        } else {
            ((clamped + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    fun toLinearBaseColorFactor(hex: String, scale: Float = 1f): FloatArray {
        val srgb = parseHexRgb(hex)
        return floatArrayOf(
            srgbToLinear((srgb[0] * scale).coerceIn(0f, 1f)),
            srgbToLinear((srgb[1] * scale).coerceIn(0f, 1f)),
            srgbToLinear((srgb[2] * scale).coerceIn(0f, 1f)),
            1f
        )
    }

    fun lerpSrgb(hexA: String, hexB: String, t: Float): String {
        val a = parseHexRgb(hexA)
        val b = parseHexRgb(hexB)
        val k = t.coerceIn(0f, 1f)
        val r = ((a[0] + (b[0] - a[0]) * k) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((a[1] + (b[1] - a[1]) * k) * 255f).roundToInt().coerceIn(0, 255)
        val bl = ((a[2] + (b[2] - a[2]) * k) * 255f).roundToInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, bl)
    }

    fun scaleHex(hex: String, factor: Float): String {
        val rgb = parseHexRgb(hex)
        val r = (rgb[0] * factor * 255f).roundToInt().coerceIn(0, 255)
        val g = (rgb[1] * factor * 255f).roundToInt().coerceIn(0, 255)
        val b = (rgb[2] * factor * 255f).roundToInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, b)
    }

    /**
     * 对 ARGB 像素数组进行保留明暗细节与中性高光的双通道着色：
     * - 透明像素与接近纯白的高光/领口饰边保持原貌，避免眼白高光或白边被染脏；
     * - 高亮彩色饰边（若提供 `accentHex`）可映射到角色强调色，主布料则保留褶皱明暗映射到 `primaryHex`。
     */
    fun recolorArgbPixels(
        pixels: IntArray,
        primaryHex: String,
        accentHex: String? = null,
        preserveNeutralHighlights: Boolean = true,
        neutralSaturationMax: Float = 0.14f,
        neutralLuminanceMin: Float = 0.78f,
        minPrimaryBoost: Float = 0.22f,
        coolTonesOnly: Boolean = false
    ) {
        val primary = parseHexRgb(primaryHex)
        val accent = accentHex?.let { parseHexRgb(it) }
        val primaryLum = (0.2126f * primary[0] + 0.7152f * primary[1] + 0.0722f * primary[2]).coerceAtLeast(0.05f)
        // 对于深色服装（如露娜黑紫裙、K-07 炭黑机甲），提升中等明度映射下限以保留褶皱立体感
        val lumLift = if (primaryLum < 0.25f) minPrimaryBoost else 0.12f

        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            if (a < 8) continue

            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f

            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = if (maxC > 1e-4f) (maxC - minC) / maxC else 0f
            val lum = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)

            // 对身体贴图（肤色 + 丝袜/袖套）仅重着色冷色调区域，完整保护暖色调皮肤
            if (coolTonesOnly && (b <= r + 0.03f || sat < 0.15f)) {
                continue
            }

            if (preserveNeutralHighlights && sat <= neutralSaturationMax && lum >= neutralLuminanceMin) {
                continue
            }

            // 服装上的高饱和亮色滚边/条纹切换到角色强调色
            val target = if (accent != null && sat >= 0.30f && lum >= 0.56f) {
                accent
            } else {
                primary
            }

            // 用明度调制目标色：暗褶皱保持层次，中等偏亮区域贴近目标色相
            val shading = (lumLift + (1f - lumLift) * (lum / 0.72f).coerceIn(0f, 1.35f)).coerceIn(0.08f, 1.35f)
            val nr = ((target[0] * shading) * 255f).roundToInt().coerceIn(0, 255)
            val ng = ((target[1] * shading) * 255f).roundToInt().coerceIn(0, 255)
            val nb = ((target[2] * shading) * 255f).roundToInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    data class GlbChunks(
        val root: JsonObject,
        val bin: ByteArray
    )

    fun parseGlb(glbBytes: ByteArray): GlbChunks? {
        if (glbBytes.size < 20) return null
        val buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.getInt(0)
        val version = buf.getInt(4)
        val totalLen = buf.getInt(8)
        if (magic != GLB_MAGIC || version != GLB_VERSION || totalLen > glbBytes.size) return null

        var offset = 12
        var jsonObj: JsonObject? = null
        var binBytes: ByteArray? = null

        while (offset + 8 <= totalLen) {
            val chunkLen = buf.getInt(offset)
            val chunkType = buf.getInt(offset + 4)
            val dataStart = offset + 8
            if (chunkLen < 0 || dataStart + chunkLen > totalLen) return null
            if (chunkType == CHUNK_JSON && jsonObj == null) {
                val jsonStr = String(glbBytes, dataStart, chunkLen, StandardCharsets.UTF_8).trim()
                jsonObj = JsonParser.parseString(jsonStr).asJsonObject
            } else if (chunkType == CHUNK_BIN && binBytes == null) {
                binBytes = glbBytes.copyOfRange(dataStart, dataStart + chunkLen)
            }
            offset = dataStart + chunkLen
        }

        val root = jsonObj ?: return null
        return GlbChunks(root, binBytes ?: ByteArray(0))
    }

    fun extractImageBytesByName(chunks: GlbChunks): Map<String, ByteArray> {
        val images = chunks.root.getAsJsonArray("images") ?: return emptyMap()
        val bufferViews = chunks.root.getAsJsonArray("bufferViews") ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        for (i in 0 until images.size()) {
            val img = images[i].asJsonObject
            val name = img.get("name")?.asString ?: continue
            val bvIdx = img.get("bufferView")?.asInt ?: continue
            if (bvIdx !in 0 until bufferViews.size()) continue
            val bv = bufferViews[bvIdx].asJsonObject
            val byteOffset = bv.get("byteOffset")?.asInt ?: 0
            val byteLength = bv.get("byteLength")?.asInt ?: 0
            if (byteOffset >= 0 && byteLength > 0 && byteOffset + byteLength <= chunks.bin.size) {
                out[name] = chunks.bin.copyOfRange(byteOffset, byteOffset + byteLength)
            }
        }
        return out
    }

    /**
     * 更新材质 `baseColorFactor` 并替换指定的内嵌图像字节，重建完全合法且 4 字节对齐的 GLB 二进制包。
     */
    fun patchGlb(
        originalGlb: ByteArray,
        materialBaseColorOverrides: Map<String, FloatArray>,
        imageByteOverrides: Map<String, ByteArray>
    ): ByteArray? {
        val chunks = parseGlb(originalGlb) ?: return null
        val root = chunks.root

        // 1. 覆写材质因子
        val materials = root.getAsJsonArray("materials")
        if (materials != null && materialBaseColorOverrides.isNotEmpty()) {
            for (i in 0 until materials.size()) {
                val mat = materials[i].asJsonObject
                val name = mat.get("name")?.asString ?: continue
                val override = materialBaseColorOverrides[name] ?: continue
                val pbr = mat.getAsJsonObject("pbrMetallicRoughness") ?: JsonObject().also {
                    mat.add("pbrMetallicRoughness", it)
                }
                val arr = JsonArray(4)
                for (c in 0 until 4) {
                    arr.add(override.getOrElse(c) { 1f })
                }
                pbr.add("baseColorFactor", arr)
            }
        }

        // 2. 替换指定图像所在的 bufferView
        val images = root.getAsJsonArray("images")
        val bufferViews = root.getAsJsonArray("bufferViews")
        val bvReplacements = HashMap<Int, ByteArray>()
        if (images != null && bufferViews != null && imageByteOverrides.isNotEmpty()) {
            for (i in 0 until images.size()) {
                val img = images[i].asJsonObject
                val name = img.get("name")?.asString ?: continue
                val replacement = imageByteOverrides[name] ?: continue
                val bvIdx = img.get("bufferView")?.asInt ?: continue
                if (bvIdx in 0 until bufferViews.size()) {
                    bvReplacements[bvIdx] = replacement
                }
            }
        }

        // 3. 重建 BIN 块：如果所有 bufferView 无重叠则按顺序紧缩重建，否则回退安全追加模式
        val newBinBytes = if (bufferViews == null || bvReplacements.isEmpty()) {
            chunks.bin
        } else {
            rebuildBinBuffer(chunks.bin, bufferViews, bvReplacements)
        }

        // 更新 buffers[0].byteLength
        val buffers = root.getAsJsonArray("buffers")
        if (buffers != null && buffers.size() > 0) {
            buffers[0].asJsonObject.addProperty("byteLength", newBinBytes.size)
        }

        return serializeGlb(root, newBinBytes)
    }

    private fun rebuildBinBuffer(
        oldBin: ByteArray,
        bufferViews: JsonArray,
        replacements: Map<Int, ByteArray>
    ): ByteArray {
        data class Span(val index: Int, val start: Int, val len: Int)
        val spans = (0 until bufferViews.size()).map { idx ->
            val bv = bufferViews[idx].asJsonObject
            val start = bv.get("byteOffset")?.asInt ?: 0
            val len = bv.get("byteLength")?.asInt ?: 0
            Span(idx, start, len)
        }.sortedBy { it.start }

        var hasOverlap = false
        for (k in 0 until spans.size - 1) {
            if (spans[k].start + spans[k].len > spans[k + 1].start) {
                hasOverlap = true
                break
            }
        }

        val out = ByteArrayOutputStream(oldBin.size + 65536)
        if (hasOverlap) {
            out.write(oldBin)
            for ((bvIdx, bytes) in replacements) {
                while (out.size() % 4 != 0) out.write(0)
                val newOff = out.size()
                out.write(bytes)
                val bv = bufferViews[bvIdx].asJsonObject
                bv.addProperty("byteOffset", newOff)
                bv.addProperty("byteLength", bytes.size)
            }
            while (out.size() % 4 != 0) out.write(0)
            return out.toByteArray()
        }

        for (span in spans) {
            while (out.size() % 4 != 0) out.write(0)
            val newStart = out.size()
            val replacement = replacements[span.index]
            if (replacement != null) {
                out.write(replacement)
                val bv = bufferViews[span.index].asJsonObject
                bv.addProperty("byteOffset", newStart)
                bv.addProperty("byteLength", replacement.size)
            } else if (span.len > 0 && span.start >= 0 && span.start + span.len <= oldBin.size) {
                out.write(oldBin, span.start, span.len)
                val bv = bufferViews[span.index].asJsonObject
                bv.addProperty("byteOffset", newStart)
            }
        }
        while (out.size() % 4 != 0) out.write(0)
        return out.toByteArray()
    }

    private fun serializeGlb(root: JsonObject, binBytes: ByteArray): ByteArray {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val jsonRaw = gson.toJson(root).toByteArray(StandardCharsets.UTF_8)
        val jsonPad = (4 - (jsonRaw.size % 4)) % 4
        val jsonChunkLen = jsonRaw.size + jsonPad

        val binPad = (4 - (binBytes.size % 4)) % 4
        val binChunkLen = binBytes.size + binPad

        val hasBin = binChunkLen > 0
        val totalLen = 12 + 8 + jsonChunkLen + if (hasBin) 8 + binChunkLen else 0

        val out = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(GLB_MAGIC)
        bb.putInt(GLB_VERSION)
        bb.putInt(totalLen)

        bb.putInt(jsonChunkLen)
        bb.putInt(CHUNK_JSON)
        bb.put(jsonRaw)
        repeat(jsonPad) { bb.put(0x20.toByte()) }

        if (hasBin) {
            bb.putInt(binChunkLen)
            bb.putInt(CHUNK_BIN)
            bb.put(binBytes)
            repeat(binPad) { bb.put(0x00.toByte()) }
        }
        return out
    }
}
