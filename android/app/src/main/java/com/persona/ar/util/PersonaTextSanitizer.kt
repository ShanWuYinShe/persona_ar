package com.persona.ar.util

/**
 * 文本控制标签与记忆分类映射工具（纯 Kotlin，供端侧流式气泡净化与手账分类筛选共测）。
 */
object PersonaTextSanitizer {

    private val fullTagRegex = Regex("<(emotion|action|expr):[^>]*>", RegexOption.IGNORE_CASE)
    private val trailingPartialTagRegex = Regex("<(?:e|em|emo|emot|emoti|emotio|emotion|a|ac|act|acti|actio|action|ex|exp|expr)(?::[^>]*)?$", RegexOption.IGNORE_CASE)

    /**
     * 清除服务端/模型可能泄漏的 `<emotion:...>` / `<action:...>` / `<expr:...>` 标签，
     * 同时保留 `<3`、`1 < 2` 等正常正文符号。
     */
    fun stripControlTags(raw: String, stripTrailingPartial: Boolean = true): String {
        if (raw.isEmpty()) return ""
        var cleaned = fullTagRegex.replace(raw, "")
        if (stripTrailingPartial) {
            cleaned = trailingPartialTagRegex.replace(cleaned, "")
        }
        return cleaned.trimStart()
    }
}

/**
 * 记忆分类归一化映射器：兼容后端枚举（USER_FACT / SHARED_MOMENT / EMOTIONAL_PROMISE / general）
 * 与端侧手账筛选标签（all / daily / pledge / emotion / event）。
 */
object MemoryCategoryMapper {

    enum class BadgeStyle(val filterKey: String, val label: String) {
        DAILY("daily", "日常"),
        PLEDGE("pledge", "誓约"),
        EMOTION("emotion", "情感"),
        EVENT("event", "约定")
    }

    fun normalizeFilterKey(rawCategory: String?): String {
        return when (rawCategory?.trim()?.uppercase()) {
            "PLEDGE", "EMOTIONAL_PROMISE", "PROMISE" -> "pledge"
            "EMOTION", "FEELING", "MOOD" -> "emotion"
            "EVENT", "SHARED_MOMENT", "MOMENT", "APPOINTMENT" -> "event"
            "DAILY", "USER_FACT", "GENERAL", "FACT", "", null -> "daily"
            else -> "daily"
        }
    }

    fun matchesFilter(memoryCategory: String?, selectedFilter: String): Boolean {
        if (selectedFilter.equals("all", ignoreCase = true)) return true
        return normalizeFilterKey(memoryCategory).equals(selectedFilter, ignoreCase = true)
    }

    fun resolveBadgeStyle(rawCategory: String?): BadgeStyle {
        return when (normalizeFilterKey(rawCategory)) {
            "pledge" -> BadgeStyle.PLEDGE
            "emotion" -> BadgeStyle.EMOTION
            "event" -> BadgeStyle.EVENT
            else -> BadgeStyle.DAILY
        }
    }

    /**
     * 将前端创建记忆时选择的 UI 分类映射到服务端标准分类，同时保持语义清晰。
     */
    fun toBackendCategory(uiCategory: String): String {
        return when (uiCategory.trim().lowercase()) {
            "pledge" -> "EMOTIONAL_PROMISE"
            "event" -> "SHARED_MOMENT"
            "emotion" -> "SHARED_MOMENT"
            "daily" -> "USER_FACT"
            else -> uiCategory.ifBlank { "SHARED_MOMENT" }
        }
    }
}
