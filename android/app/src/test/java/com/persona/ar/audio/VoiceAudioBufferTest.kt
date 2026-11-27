package com.persona.ar.audio

import com.persona.ar.character.loader.PersonaModelManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceAudioBufferTest {

    @Test
    fun continuousPreRollDropsLongSilenceAndKeepsRecentPreRollOnSpeechStart() {
        // 8 字节预卷，最大 128 字节单轮缓存
        val buf = VoiceAudioBuffer(preRollCapacityBytes = 8, maxUtteranceBytes = 128)
        buf.reset(usePreRollRing = true)

        // 连续写入 20 字节的静默噪音（应该被环形缓冲覆盖，仅保留最后 8 字节 12..19）
        val silence = ByteArray(20) { it.toByte() }
        val cappedBeforeSpeech = buf.appendPcm(silence)
        assertFalse(cappedBeforeSpeech)
        assertEquals(0, buf.utteranceByteCount())
        assertNull(buf.takeWavAndReset(keepPreRollMode = true))

        // 重新写入 10 字节静默（环内保留最后 8 字节: 2..9），再触发发声
        val preNoise = ByteArray(10) { it.toByte() }
        buf.appendPcm(preNoise)
        buf.markSpeechStarted()
        // 仅 8 字节 pre-roll 进入 utterance
        assertEquals(8, buf.utteranceByteCount())

        // 发声后继续写入 4 字节说话内容
        buf.appendPcm(byteArrayOf(100, 101, 102, 103))
        val wav = buf.takeWavAndReset(sampleRate = 16000, keepPreRollMode = true)
        assertNotNull(wav)
        assertEquals(44 + 12, wav!!.size)
        // 验证 WAV RIFF/WAVE 头与 data 长度
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(12, (wav[40].toInt() and 0xff) or ((wav[41].toInt() and 0xff) shl 8))
        // 前 8 字节应是 preNoise 的尾部 [2..9]，接着 [100..103]
        assertArrayEquals(
            byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 100, 101, 102, 103),
            wav.copyOfRange(44, wav.size)
        )
        // 重置后恢复纯预卷模式
        assertEquals(0, buf.utteranceByteCount())
    }

    @Test
    fun maxUtteranceCapPreventsUnboundedMemoryGrowth() {
        val buf = VoiceAudioBuffer(preRollCapacityBytes = 4, maxUtteranceBytes = 16)
        buf.reset(usePreRollRing = false)

        val chunk1 = ByteArray(10) { 1 }
        assertFalse(buf.appendPcm(chunk1))
        val chunk2 = ByteArray(10) { 2 }
        assertTrue(buf.appendPcm(chunk2))
        assertEquals(16, buf.utteranceByteCount())

        val wav = buf.takeWavAndReset()!!
        assertEquals(44 + 16, wav.size)
    }

    @Test
    fun pcm16ToLeBytesAndPeakEncodesLittleEndianAndClampsAmplitude() {
        val samples = shortArrayOf(0, 6000, -12000, 24000)
        val (bytes, peak) = VoiceAudioBuffer.pcm16ToLeBytesAndPeak(samples, samples.size)
        assertEquals(8, bytes.size)
        assertEquals(1.0f, peak, 1e-4f)
        // 6000 = 0x1770 -> little-endian [0x70, 0x17]
        assertEquals(0x70.toByte(), bytes[2])
        assertEquals(0x17.toByte(), bytes[3])
    }

    @Test
    fun builtinModelsAndAssetManifestsHaveDistinctFemaleVoices() {
        val manager = PersonaModelManager(context = null)
        val models = manager.storeModels.value
        assertEquals(4, models.size)

        val expectedVoices = mapOf(
            "hoshino" to "zh-CN-XiaoyiNeural",
            "luna" to "zh-CN-XiaoxiaoNeural",
            "airi" to "zh-TW-HsiaoYuNeural",
            "cyber_k" to "zh-TW-HsiaoChenNeural"
        )
        models.forEach { m ->
            assertEquals("builtin ${m.id} defaultVoice", expectedVoices[m.id], m.defaultVoice)
        }
        assertEquals(4, models.map { it.defaultVoice }.distinct().size)

        // 同步校验 assets/models/*.persona.json 资产配置完全一致
        val assetsDir = listOf(
            File("src/main/assets/models"),
            File("app/src/main/assets/models"),
            File("android/app/src/main/assets/models")
        ).firstOrNull { it.isDirectory }
        assertNotNull("找不到 assets/models 目录", assetsDir)
        expectedVoices.forEach { (id, voice) ->
            val file = File(assetsDir, "$id.persona.json")
            assertTrue("缺少 ${file.path}", file.exists())
            val content = file.readText()
            assertTrue("${file.name} 应包含 $voice", content.contains("\"$voice\""))
        }
    }
}
