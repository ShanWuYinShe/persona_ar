package com.persona.ar.character.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.persona.ar.data.model.PersonaModelManifest
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 基于角色 Manifest 的色板实时生成并磁盘缓存差异化 GLB 3D 资产。
 * - `hoshino` 保持原生资源零改动开销；
 * - `luna` / `airi` / `cyber_k` 等伙伴动态覆写发色、眉睫、眼瞳、服装与鞋履 PBR 材质和内嵌纹理，
 *   并按版本与色板哈希持久化至 `cacheDir`，二次加载零重复解码。
 */
object PersonaGlbStyler {
    private const val TAG = "PersonaGlbStyler"
    private const val CACHE_DIR_NAME = "persona_styled_glb_v2"

    fun loadStyledGlbBytes(
        context: Context,
        assetPath: String,
        manifest: PersonaModelManifest?
    ): ByteArray {
        if (manifest == null || manifest.id == "hoshino") {
            return context.assets.open(assetPath).use { it.readBytes() }
        }

        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val paletteKey = listOf(
            manifest.id,
            manifest.version,
            manifest.hairColorHex,
            manifest.eyeColorHex,
            manifest.skirtColorHex,
            manifest.previewColorHex
        ).joinToString("_").replace(Regex("[^a-zA-Z0-9_-]"), "")
        val cacheFile = File(cacheDir, "$paletteKey.glb")

        if (cacheFile.exists() && cacheFile.length() > 100_000L) {
            runCatching { return cacheFile.readBytes() }
                .onFailure { Log.w(TAG, "读取缓存 GLB 失败，将重新生成: ${cacheFile.name}", it) }
        }

        val baseBytes = context.assets.open(assetPath).use { it.readBytes() }
        val styledBytes = buildStyledGlbBytes(baseBytes, manifest)
        if (styledBytes != null) {
            runCatching {
                val tmp = File(cacheDir, "$paletteKey.tmp")
                tmp.writeBytes(styledBytes)
                if (cacheFile.exists()) cacheFile.delete()
                tmp.renameTo(cacheFile)
            }.onFailure {
                Log.w(TAG, "写入风格化 GLB 缓存失败: ${cacheFile.name}", it)
            }
            return styledBytes
        }
        return baseBytes
    }

    private fun buildStyledGlbBytes(baseBytes: ByteArray, manifest: PersonaModelManifest): ByteArray? {
        val chunks = PersonaPaletteMath.parseGlb(baseBytes) ?: return null
        val sourceImages = PersonaPaletteMath.extractImageBytesByName(chunks)

        val hairHex = manifest.hairColorHex.ifBlank { "#E0E2EB" }
        val eyeHex = manifest.eyeColorHex.ifBlank { "#FF2E63" }
        val outfitHex = manifest.skirtColorHex.ifBlank { "#1E1A29" }
        val accentHex = manifest.previewColorHex.ifBlank { eyeHex }
        val tipHex = PersonaPaletteMath.lerpSrgb(hairHex, accentHex, 0.24f)

        val matOverrides = mapOf(
            "F00_000_HairBack_00_HAIR" to PersonaPaletteMath.toLinearBaseColorFactor(hairHex, 1.0f),
            "F00_000_Hair_00_HAIR_01" to PersonaPaletteMath.toLinearBaseColorFactor(hairHex, 1.0f),
            "F00_000_Hair_00_HAIR_02" to PersonaPaletteMath.toLinearBaseColorFactor(tipHex, 0.96f),
            "F00_000_00_FaceBrow_00_FACE" to PersonaPaletteMath.toLinearBaseColorFactor(
                PersonaPaletteMath.scaleHex(hairHex, 0.65f),
                1.0f
            ),
            "F00_000_00_FaceEyelash_00_FACE" to PersonaPaletteMath.toLinearBaseColorFactor(
                PersonaPaletteMath.scaleHex(hairHex, 0.40f),
                1.0f
            )
        )

        val imgOverrides = LinkedHashMap<String, ByteArray>()
        recolorPngAsset(
            sourceImages["F00_000_EyeIris_00"],
            primaryHex = eyeHex,
            accentHex = null,
            preserveNeutralHighlights = true,
            neutralSaturationMax = 0.16f,
            neutralLuminanceMin = 0.84f,
            minPrimaryBoost = 0.16f
        )?.let { imgOverrides["F00_000_EyeIris_00"] = it }

        recolorPngAsset(
            sourceImages["F00_000_Hair_00_03"],
            primaryHex = tipHex,
            accentHex = null,
            preserveNeutralHighlights = false,
            minPrimaryBoost = 0.20f
        )?.let { imgOverrides["F00_000_Hair_00_03"] = it }

        recolorPngAsset(
            sourceImages["F00_002_Body_00"],
            primaryHex = outfitHex,
            accentHex = accentHex,
            preserveNeutralHighlights = true,
            neutralSaturationMax = 0.15f,
            neutralLuminanceMin = 0.78f,
            minPrimaryBoost = 0.24f,
            coolTonesOnly = true
        )?.let { imgOverrides["F00_002_Body_00"] = it }

        recolorPngAsset(
            sourceImages["F00_002_Onepiece_01"],
            primaryHex = outfitHex,
            accentHex = accentHex,
            preserveNeutralHighlights = true,
            neutralSaturationMax = 0.15f,
            neutralLuminanceMin = 0.76f,
            minPrimaryBoost = 0.24f
        )?.let { imgOverrides["F00_002_Onepiece_01"] = it }

        recolorPngAsset(
            sourceImages["F00_002_Shoes_01"],
            primaryHex = PersonaPaletteMath.scaleHex(outfitHex, 0.85f),
            accentHex = accentHex,
            preserveNeutralHighlights = true,
            neutralSaturationMax = 0.15f,
            neutralLuminanceMin = 0.82f,
            minPrimaryBoost = 0.22f
        )?.let { imgOverrides["F00_002_Shoes_01"] = it }

        return PersonaPaletteMath.patchGlb(baseBytes, matOverrides, imgOverrides)
    }

    private fun recolorPngAsset(
        pngBytes: ByteArray?,
        primaryHex: String,
        accentHex: String?,
        preserveNeutralHighlights: Boolean,
        neutralSaturationMax: Float = 0.14f,
        neutralLuminanceMin: Float = 0.78f,
        minPrimaryBoost: Float = 0.22f,
        coolTonesOnly: Boolean = false
    ): ByteArray? {
        if (pngBytes == null || pngBytes.isEmpty()) return null
        val decoded = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return null
        val mutable = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
        }
        val width = mutable.width
        val height = mutable.height
        val pixels = IntArray(width * height)
        mutable.getPixels(pixels, 0, width, 0, 0, width, height)
        PersonaPaletteMath.recolorArgbPixels(
            pixels = pixels,
            primaryHex = primaryHex,
            accentHex = accentHex,
            preserveNeutralHighlights = preserveNeutralHighlights,
            neutralSaturationMax = neutralSaturationMax,
            neutralLuminanceMin = neutralLuminanceMin,
            minPrimaryBoost = minPrimaryBoost,
            coolTonesOnly = coolTonesOnly
        )
        mutable.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArrayOutputStream(pngBytes.size)
        mutable.compress(Bitmap.CompressFormat.PNG, 100, out)
        mutable.recycle()
        return out.toByteArray()
    }
}
