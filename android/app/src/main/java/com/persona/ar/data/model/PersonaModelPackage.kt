package com.persona.ar.data.model

import com.google.gson.annotations.SerializedName

enum class PersonaModelType {
    GLTF_3D      // 3D glTF/GLB 空间模型
}

/**
 * 3D 角色模型资产包 Manifest 配置规范
 */
data class PersonaModelManifest(
    val id: String,
    val name: String,
    val author: String = "Official",
    val version: String = "1.0.0",
    @SerializedName("model_type") val modelType: PersonaModelType = PersonaModelType.GLTF_3D,
    /** 3D 模型资产在 assets 内的相对路径（如 models/hoshino/figure.glb） */
    @SerializedName("model_asset") val modelAsset: String = "models/hoshino/figure.glb",
    val description: String,
    @SerializedName("preview_color") val previewColorHex: String = "#FF7B61FF",
    @SerializedName("hair_color") val hairColorHex: String = "#6B4E71",
    @SerializedName("eye_color") val eyeColorHex: String = "#5D54A8",
    @SerializedName("skirt_color") val skirtColorHex: String = "#3B4A6B",
    @SerializedName("default_voice") val defaultVoice: String = "zh-CN-XiaoyiNeural",
    @SerializedName("greeting_text") val greetingText: String,
    val tags: List<String> = emptyList(),
    val isInstalled: Boolean = true,
    @SerializedName("downloads_count") val downloadsCount: Int = 0,
    val rating: Float = 5.0f
)

/**
 * 商城展示的角色分类
 */
enum class StoreCategory {
    ALL,
    RECOMMENDED,
    OFFICIAL,
    WORKSHOP_CREATORS
}
