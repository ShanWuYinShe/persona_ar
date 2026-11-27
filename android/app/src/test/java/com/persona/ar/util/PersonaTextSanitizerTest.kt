package com.persona.ar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTextSanitizerTest {

    @Test
    fun stripControlTagsRemovesCompleteAndTrailingPartialTagsWhilePreservingMath() {
        val full = "<emotion:JOY> <action:WAVE> <expr:SMILE> 早上好！I <3 you and 1 < 2"
        assertEquals("早上好！I <3 you and 1 < 2", PersonaTextSanitizer.stripControlTags(full))

        val midStream = "今天阳光真不错呢 <emotion:JO"
        assertEquals("今天阳光真不错呢 ", PersonaTextSanitizer.stripControlTags(midStream, stripTrailingPartial = true))

        val normalHeart = "最喜欢你啦 <3"
        assertEquals("最喜欢你啦 <3", PersonaTextSanitizer.stripControlTags(normalHeart))
    }

    @Test
    fun memoryCategoryMapperBridgesBackendEnumsAndUiFilterKeys() {
        assertTrue(MemoryCategoryMapper.matchesFilter("USER_FACT", "all"))
        assertTrue(MemoryCategoryMapper.matchesFilter("USER_FACT", "daily"))
        assertTrue(MemoryCategoryMapper.matchesFilter("SHARED_MOMENT", "event"))
        assertTrue(MemoryCategoryMapper.matchesFilter("EMOTIONAL_PROMISE", "pledge"))
        assertTrue(MemoryCategoryMapper.matchesFilter("general", "daily"))
        assertFalse(MemoryCategoryMapper.matchesFilter("EMOTIONAL_PROMISE", "daily"))

        assertEquals(MemoryCategoryMapper.BadgeStyle.PLEDGE, MemoryCategoryMapper.resolveBadgeStyle("EMOTIONAL_PROMISE"))
        assertEquals(MemoryCategoryMapper.BadgeStyle.EVENT, MemoryCategoryMapper.resolveBadgeStyle("SHARED_MOMENT"))
        assertEquals("EMOTIONAL_PROMISE", MemoryCategoryMapper.toBackendCategory("pledge"))
        assertEquals("USER_FACT", MemoryCategoryMapper.toBackendCategory("daily"))
    }
}
