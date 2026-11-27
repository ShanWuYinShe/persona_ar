package com.persona.ar.data.preferences

import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.VoiceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaSettingsCodecTest {

    @Test
    fun normalizeServerUrlHandlesBlankMissingSchemeAndTrailingSlash() {
        assertEquals(PersonaSettingsSnapshot.DEFAULT_SERVER_URL, PersonaSettingsCodec.normalizeServerUrl("   "))
        assertEquals("http://192.168.1.50:8000/", PersonaSettingsCodec.normalizeServerUrl("192.168.1.50:8000"))
        assertEquals("https://api.example.com/", PersonaSettingsCodec.normalizeServerUrl("  https://api.example.com  "))
        assertEquals("http://127.0.0.1:8000/", PersonaSettingsCodec.normalizeServerUrl("http://127.0.0.1:8000/"))
    }

    @Test
    fun encodeAndDecodeRoundTripPreservesUserConfig() {
        val original = PersonaSettingsSnapshot(
            serverBaseUrl = "192.168.31.10:8000",
            llmProvider = "openai",
            llmBaseUrl = "https://api.deepseek.com",
            llmApiKey = "sk-test-1234",
            llmModel = "deepseek-chat",
            asrProvider = "groq",
            asrBaseUrl = "https://api.groq.com/openai/v1",
            asrApiKey = "gsk-test-999",
            asrModel = "whisper-large-v3",
            asrLanguage = "zh",
            voiceMode = VoiceMode.HOLD_TO_TALK.name,
            bubbleDisplayMode = BubbleDisplayMode.AUTO_FADE_OUT.name,
            activeModelId = "luna",
            installedModelIds = listOf("hoshino", "luna", "airi"),
            bondLevel = 4,
            bondExp = 120,
            bondExpNext = 337
        )

        val encoded = PersonaSettingsCodec.encode(original)
        val decoded = PersonaSettingsCodec.decode(encoded)

        assertEquals("http://192.168.31.10:8000/", decoded.serverBaseUrl)
        assertEquals("openai", decoded.llmProvider)
        assertEquals("sk-test-1234", decoded.llmApiKey)
        assertEquals(VoiceMode.HOLD_TO_TALK, decoded.resolvedVoiceMode())
        assertEquals(BubbleDisplayMode.AUTO_FADE_OUT, decoded.resolvedBubbleDisplayMode())
        assertEquals("luna", decoded.activeModelId)
        assertEquals(listOf("hoshino", "luna", "airi"), decoded.installedModelIds)
        assertEquals(4, decoded.bondLevel)
        assertEquals(120, decoded.bondExp)
        assertEquals(337, decoded.bondExpNext)
    }

    @Test
    fun decodeHandlesCorruptedAndOutOfRangeValuesSafely() {
        val corruptDecoded = PersonaSettingsCodec.decode("{not-valid-json")
        assertEquals(PersonaSettingsSnapshot.DEFAULT_SERVER_URL, corruptDecoded.serverBaseUrl)
        assertEquals("hoshino", corruptDecoded.activeModelId)

        val weirdJson = """
            {
              "server_base_url": "",
              "voice_mode": "UNKNOWN_ENUM",
              "bubble_display_mode": "BROKEN",
              "installed_model_ids": [],
              "bond_level": 99,
              "bond_exp": -50,
              "bond_exp_next": 10
            }
        """.trimIndent()
        val sanitized = PersonaSettingsCodec.decode(weirdJson)
        assertEquals(VoiceMode.CONTINUOUS_NATURAL, sanitized.resolvedVoiceMode())
        assertEquals(BubbleDisplayMode.PERSISTENT, sanitized.resolvedBubbleDisplayMode())
        assertTrue(sanitized.installedModelIds.containsAll(listOf("hoshino", "luna")))
        assertEquals(10, sanitized.bondLevel)
        assertEquals(0, sanitized.bondExp)
        assertEquals(100, sanitized.bondExpNext)
    }
}
