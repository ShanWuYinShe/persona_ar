package com.persona.ar.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 线程安全的 16-bit PCM 音频缓冲与 WAV 编码器：
 * - 连麦模式下保留固定长度（默认 320ms）的预卷环形缓冲，仅在触发 VAD 发声后才将首音节与后续语音写入主缓冲，
 *   防止长时间静默监听导致内存无限增长及末尾无效静音送入 ASR；
 * - 强制设置单轮最大录制字节上限，杜绝长时间噪音或异常长按造成 OOM。
 */
class VoiceAudioBuffer(
    private val preRollCapacityBytes: Int = DEFAULT_PRE_ROLL_BYTES,
    private val maxUtteranceBytes: Int = DEFAULT_MAX_UTTERANCE_BYTES
) {
    private val lock = Any()
    private val utteranceStream = ByteArrayOutputStream()
    private val preRollBuf = ByteArray(preRollCapacityBytes.coerceAtLeast(0))
    private var preRollHead = 0
    private var preRollSize = 0
    private var preRollEnabled = false
    private var capturingUtterance = true

    fun reset(usePreRollRing: Boolean) {
        synchronized(lock) {
            utteranceStream.reset()
            preRollHead = 0
            preRollSize = 0
            preRollEnabled = usePreRollRing && preRollBuf.isNotEmpty()
            capturingUtterance = !preRollEnabled
        }
    }

    fun markSpeechStarted() {
        synchronized(lock) {
            if (capturingUtterance) return
            capturingUtterance = true
            if (preRollSize > 0 && preRollBuf.isNotEmpty()) {
                val cap = preRollBuf.size
                val start = (preRollHead - preRollSize + cap) % cap
                val firstLen = minOf(preRollSize, cap - start)
                appendToUtteranceLocked(preRollBuf, start, firstLen)
                if (firstLen < preRollSize) {
                    appendToUtteranceLocked(preRollBuf, 0, preRollSize - firstLen)
                }
                preRollHead = 0
                preRollSize = 0
            }
        }
    }

    /**
     * 写入小端序 16-bit PCM 字节。若达到单轮最大字节上限则返回 true，提示上层主动切分。
     */
    fun appendPcm(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length <= 0) return false
        synchronized(lock) {
            if (!capturingUtterance) {
                if (preRollBuf.isEmpty()) return false
                val cap = preRollBuf.size
                val takeStart = (length - cap).coerceAtLeast(0)
                for (i in takeStart until length) {
                    preRollBuf[preRollHead] = bytes[i]
                    preRollHead = (preRollHead + 1) % cap
                    if (preRollSize < cap) preRollSize++
                }
                return false
            }
            appendToUtteranceLocked(bytes, 0, length)
            return utteranceStream.size() >= maxUtteranceBytes
        }
    }

    private fun appendToUtteranceLocked(bytes: ByteArray, offset: Int, length: Int) {
        val remaining = (maxUtteranceBytes - utteranceStream.size()).coerceAtLeast(0)
        if (remaining <= 0) return
        val toWrite = minOf(length, remaining)
        if (toWrite > 0) {
            utteranceStream.write(bytes, offset, toWrite)
        }
    }

    fun utteranceByteCount(): Int = synchronized(lock) { utteranceStream.size() }

    fun takeWavAndReset(sampleRate: Int = 16000, keepPreRollMode: Boolean = false): ByteArray? {
        val pcmData = synchronized(lock) {
            val bytes = utteranceStream.toByteArray()
            utteranceStream.reset()
            preRollHead = 0
            preRollSize = 0
            preRollEnabled = keepPreRollMode && preRollBuf.isNotEmpty()
            capturingUtterance = !preRollEnabled
            bytes
        }
        if (pcmData.isEmpty()) return null
        return encodeWav(pcmData, sampleRate, 1, 16)
    }

    companion object {
        // 16kHz * 16bit mono = 32000 bytes/sec -> 320ms 预卷约 10240 字节，30s 上限约 960KB
        const val DEFAULT_PRE_ROLL_BYTES = 10_240
        const val DEFAULT_MAX_UTTERANCE_BYTES = 32_000 * 30

        fun pcm16ToLeBytesAndPeak(samples: ShortArray, count: Int): Pair<ByteArray, Float> {
            if (count <= 0) return ByteArray(0) to 0f
            var maxAmp = 0
            val byteBuf = ByteArray(count * 2)
            for (i in 0 until count) {
                val sample = samples[i].toInt()
                val a = abs(sample)
                if (a > maxAmp) maxAmp = a
                byteBuf[i * 2] = (sample and 0xff).toByte()
                byteBuf[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
            }
            val normalized = (maxAmp / 12000f).coerceIn(0f, 1f)
            return byteBuf to normalized
        }

        fun encodeWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
            val totalAudioLen = pcmData.size
            val totalDataLen = totalAudioLen + 36
            val byteRate = sampleRate * channels * bitsPerSample / 8

            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            val wavData = ByteArray(44 + totalAudioLen)
            System.arraycopy(header, 0, wavData, 0, 44)
            System.arraycopy(pcmData, 0, wavData, 44, totalAudioLen)
            return wavData
        }
    }
}
