package com.persona.ar.character.loader

import android.content.Context
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaModelType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class PersonaModelManager(private val context: Context? = null) {

    // 官方与工坊内置模型清单
    private val builtinModels = listOf(
        PersonaModelManifest(
            id = "hoshino",
            name = "星野 (Hoshino)",
            author = "Persona Studio",
            version = "1.2.0",
            modelType = PersonaModelType.GLTF_3D,
            modelAsset = "models/hoshino/figure.glb",
            description = "投射在现实中的二次元傲娇元气少女，心软口硬，对现实世界充满好奇。",
            previewColorHex = "#FF7B61FF",
            hairColorHex = "#6B4E71",
            eyeColorHex = "#5D54A8",
            skirtColorHex = "#3B4A6B",
            defaultVoice = "zh-CN-XiaoyiNeural",
            greetingText = "哼哼，现实世界的信号终于连上啦！我就站在你的书桌前哦~",
            tags = listOf("元气", "傲娇", "双马尾", "官方看板娘"),
            isInstalled = true,
            downloadsCount = 12890,
            rating = 4.9f
        ),
        PersonaModelManifest(
            id = "luna",
            name = "露娜 (Luna)",
            author = "SilverStar Works",
            version = "1.0.4",
            modelType = PersonaModelType.SPRITE_2_5D,
            description = "银白长发、赤色眼瞳的高冷毒舌小魔女。嘴上嫌弃，其实很黏人。",
            previewColorHex = "#FF5277",
            hairColorHex = "#E0E2EB", // 银白发色
            eyeColorHex = "#FF2E63", // 赤瞳
            skirtColorHex = "#1E1A29", // 暗夜女仆裙
            defaultVoice = "zh-CN-XiaoxiaoNeural",
            greetingText = "居然把我召唤到了三维现实中…笨蛋使魔，可别指望我会夸你。",
            tags = listOf("白毛", "红瞳", "高冷", "工坊热门"),
            isInstalled = true,
            downloadsCount = 9840,
            rating = 4.8f
        ),
        PersonaModelManifest(
            id = "airi",
            name = "艾莉 (Airi)",
            author = "WarmHeart Lab",
            version = "1.1.0",
            modelType = PersonaModelType.SPRITE_2_5D,
            description = "温柔体贴的邻家治愈学妹。在你累的时候会微笑着静静陪伴你。",
            previewColorHex = "#00E676",
            hairColorHex = "#8D6E63", // 暖栗发色
            eyeColorHex = "#43A047", // 翠绿眼眸
            skirtColorHex = "#FFF3E0", // 米白针织长裙
            defaultVoice = "zh-CN-YunjianNeural",
            greetingText = "学长，今天也辛苦啦~ 我已经投射在你的身边了，随时跟我聊聊天吧！",
            tags = listOf("治愈", "学妹", "温柔", "陪伴"),
            isInstalled = false, // 商城待下载
            downloadsCount = 15300,
            rating = 5.0f
        ),
        PersonaModelManifest(
            id = "cyber_k",
            name = "K-07 (Cyber K)",
            author = "Neon Future 3D",
            version = "2.0.0",
            modelType = PersonaModelType.GLTF_3D,
            description = "从未来赛博空间穿越而来的全息机甲少女，具备 3D 空间立体投射网格。",
            previewColorHex = "#00E5FF",
            hairColorHex = "#00E5FF", // 青蓝荧光短发
            eyeColorHex = "#76FF03", // 荧光绿目镜
            skirtColorHex = "#212121", // 战术机甲装束
            defaultVoice = "zh-CN-YunyangNeural",
            greetingText = "全息网络同步完毕。指挥官，现实环境侦测中，已准备就绪。",
            tags = listOf("3D模型", "赛博朋克", "机甲", "ARCore原生"),
            isInstalled = false, // 商城待下载
            downloadsCount = 8400,
            rating = 4.7f
        )
    )

    private val allModels: List<PersonaModelManifest> = loadModelsFromAssetsOrBuiltin()

    private val _installedModels = MutableStateFlow<List<PersonaModelManifest>>(
        allModels.filter { it.isInstalled }
    )
    val installedModels: StateFlow<List<PersonaModelManifest>> = _installedModels.asStateFlow()

    private val _storeModels = MutableStateFlow<List<PersonaModelManifest>>(allModels)
    val storeModels: StateFlow<List<PersonaModelManifest>> = _storeModels.asStateFlow()

    // 默认激活官方看板娘星野 (hoshino)：唯一拥有 3D 手办身体的人格；assets 目录按字母序列出时 airi 会排在首位，不能直接取 first()
    private val _activeModel = MutableStateFlow<PersonaModelManifest>(
        allModels.firstOrNull { it.id == "hoshino" } ?: allModels.first()
    )
    val activeModel: StateFlow<PersonaModelManifest> = _activeModel.asStateFlow()

    private fun loadModelsFromAssetsOrBuiltin(): List<PersonaModelManifest> {
        val loaded = mutableListOf<PersonaModelManifest>()
        if (context != null) {
            try {
                val assetManager = context.assets
                val files = assetManager.list("models") ?: emptyArray()
                for (file in files) {
                    if (file.endsWith(".persona.json")) {
                        val jsonStr = assetManager.open("models/$file").bufferedReader().use { it.readText() }
                        val obj = JSONObject(jsonStr)
                        val tagsArray = obj.optJSONArray("tags")
                        val tags = mutableListOf<String>()
                        if (tagsArray != null) {
                            for (i in 0 until tagsArray.length()) {
                                tags.add(tagsArray.getString(i))
                            }
                        }
                        loaded.add(
                            PersonaModelManifest(
                                id = obj.getString("id"),
                                name = obj.getString("name"),
                                author = obj.optString("author", "Persona Studio"),
                                version = obj.optString("version", "1.0.0"),
                                modelType = if (obj.optString("modelType") == "GLTF_3D") PersonaModelType.GLTF_3D else PersonaModelType.SPRITE_2_5D,
                                modelAsset = obj.optString("modelAsset").ifBlank { null },
                                description = obj.optString("description", ""),
                                previewColorHex = obj.optString("previewColorHex", "#FF7B61FF"),
                                hairColorHex = obj.optString("hairColorHex", "#6B4E71"),
                                eyeColorHex = obj.optString("eyeColorHex", "#5D54A8"),
                                skirtColorHex = obj.optString("skirtColorHex", "#3B4A6B"),
                                defaultVoice = obj.optString("defaultVoice", "zh-CN-XiaoyiNeural"),
                                greetingText = obj.optString("greetingText", "你好呀！我就在你的现实世界中。"),
                                tags = tags,
                                isInstalled = obj.optBoolean("isInstalled", false),
                                downloadsCount = obj.optInt("downloadsCount", 1000),
                                rating = obj.optDouble("rating", 4.8).toFloat()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return if (loaded.isNotEmpty()) loaded else builtinModels
    }

    /**
     * 切换当前激活的纸片人模型
     */
    fun switchModel(modelId: String): Boolean {
        val target = _installedModels.value.find { it.id == modelId }
            ?: _storeModels.value.find { it.id == modelId && it.isInstalled }
        return if (target != null) {
            _activeModel.value = target
            true
        } else {
            false
        }
    }

    /**
     * 从商城安装新模型
     */
    fun installModelFromStore(modelId: String): Boolean {
        val currentStore = _storeModels.value
        val target = currentStore.find { it.id == modelId } ?: return false
        val updated = target.copy(isInstalled = true)

        _storeModels.value = currentStore.map {
            if (it.id == modelId) updated else it
        }

        if (_installedModels.value.none { it.id == modelId }) {
            _installedModels.value = _installedModels.value + updated
        }
        return true
    }

    /**
     * 卸载自定义模型
     */
    fun uninstallModel(modelId: String): Boolean {
        if (modelId == "hoshino") return false // 默认看板娘不许卸载
        _installedModels.value = _installedModels.value.filter { it.id != modelId }
        _storeModels.value = _storeModels.value.map {
            if (it.id == modelId) it.copy(isInstalled = false) else it
        }
        if (_activeModel.value.id == modelId) {
            _activeModel.value = _installedModels.value.first()
        }
        return true
    }
}
