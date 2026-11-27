package com.persona.ar.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.VoiceMode

/**
 * 客户端持久化快照：纯 Kotlin 数据结构，可独立在 JVM 单元测试中序列化/反序列化校验。
 */
data class PersonaSettingsSnapshot(
    @SerializedName("server_base_url") val serverBaseUrl: String = DEFAULT_SERVER_URL,
    @SerializedName("llm_provider") val llmProvider: String = "mock",
    @SerializedName("llm_base_url") val llmBaseUrl: String = "https://api.deepseek.com",
    @SerializedName("llm_api_key") val llmApiKey: String = "",
    @SerializedName("llm_model") val llmModel: String = "deepseek-chat",
    @SerializedName("asr_provider") val asrProvider: String = "mock",
    @SerializedName("asr_base_url") val asrBaseUrl: String = "https://api.openai.com/v1",
    @SerializedName("asr_api_key") val asrApiKey: String = "",
    @SerializedName("asr_model") val asrModel: String = "whisper-1",
    @SerializedName("asr_language") val asrLanguage: String = "zh",
    @SerializedName("voice_mode") val voiceMode: String = VoiceMode.CONTINUOUS_NATURAL.name,
    @SerializedName("bubble_display_mode") val bubbleDisplayMode: String = BubbleDisplayMode.PERSISTENT.name,
    @SerializedName("active_model_id") val activeModelId: String = "hoshino",
    @SerializedName("installed_model_ids") val installedModelIds: List<String> = listOf("hoshino", "luna"),
    @SerializedName("bond_level") val bondLevel: Int = 1,
    @SerializedName("bond_exp") val bondExp: Int = 45,
    @SerializedName("bond_exp_next") val bondExpNext: Int = 100
) {
    fun resolvedVoiceMode(): VoiceMode =
        runCatching { VoiceMode.valueOf(voiceMode) }.getOrDefault(VoiceMode.CONTINUOUS_NATURAL)

    fun resolvedBubbleDisplayMode(): BubbleDisplayMode =
        runCatching { BubbleDisplayMode.valueOf(bubbleDisplayMode) }.getOrDefault(BubbleDisplayMode.PERSISTENT)

    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000/"
    }
}

object PersonaSettingsCodec {
    private val gson = Gson()

    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PersonaSettingsSnapshot.DEFAULT_SERVER_URL
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    fun encode(snapshot: PersonaSettingsSnapshot): String = gson.toJson(sanitize(snapshot))

    fun decode(json: String?): PersonaSettingsSnapshot {
        if (json.isNullOrBlank()) return PersonaSettingsSnapshot()
        return runCatching {
            val parsed = gson.fromJson(json, PersonaSettingsSnapshot::class.java)
                ?: PersonaSettingsSnapshot()
            sanitize(parsed)
        }.getOrDefault(PersonaSettingsSnapshot())
    }

    fun sanitize(snapshot: PersonaSettingsSnapshot): PersonaSettingsSnapshot {
        val installed = snapshot.installedModelIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("hoshino", "luna") }
            .distinct()
        val safeLevel = snapshot.bondLevel.coerceIn(1, 10)
        val safeNext = snapshot.bondExpNext.coerceAtLeast(100)
        val safeExp = snapshot.bondExp.coerceIn(0, safeNext * 2)
        return snapshot.copy(
            serverBaseUrl = normalizeServerUrl(snapshot.serverBaseUrl),
            llmProvider = snapshot.llmProvider.ifBlank { "mock" },
            llmBaseUrl = snapshot.llmBaseUrl.trim(),
            llmModel = snapshot.llmModel.ifBlank { "deepseek-chat" },
            asrProvider = snapshot.asrProvider.ifBlank { "mock" },
            asrBaseUrl = snapshot.asrBaseUrl.trim(),
            asrModel = snapshot.asrModel.ifBlank { "whisper-1" },
            asrLanguage = snapshot.asrLanguage.ifBlank { "zh" },
            voiceMode = snapshot.resolvedVoiceMode().name,
            bubbleDisplayMode = snapshot.resolvedBubbleDisplayMode().name,
            activeModelId = snapshot.activeModelId.ifBlank { "hoshino" },
            installedModelIds = installed,
            bondLevel = safeLevel,
            bondExp = safeExp,
            bondExpNext = safeNext
        )
    }
}

class PersonaPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersonaSettingsSnapshot {
        return PersonaSettingsCodec.decode(prefs.getString(KEY_SNAPSHOT, null))
    }

    fun save(snapshot: PersonaSettingsSnapshot) {
        prefs.edit()
            .putString(KEY_SNAPSHOT, PersonaSettingsCodec.encode(snapshot))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "persona_ar_settings_v1"
        private const val KEY_SNAPSHOT = "settings_snapshot_json"
    }
}
