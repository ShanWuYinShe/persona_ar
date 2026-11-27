package com.persona.ar.data.api

import android.util.Log
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.preferences.PersonaSettingsCodec
import com.persona.ar.util.PersonaTextSanitizer
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Persona AR 全双工低延迟连麦 WebSocket 客户端
 * 实现毫秒级 Barge-in 打断与流式思考态/回复接收
 */
class PersonaWebSocketClient {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false

    var onReady: ((String) -> Unit)? = null
    var onInterruptAck: (() -> Unit)? = null
    var onAsrResult: ((String) -> Unit)? = null
    var onThinkingStart: (() -> Unit)? = null
    var onStreamChunk: ((String) -> Unit)? = null
    var onResponseComplete: ((text: String, emotion: EmotionType, action: ActionType, expr: String, audioBase64: String?, affinity: Int, mood: String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    fun connect(baseUrl: String = ApiClient.baseUrl, userId: String = "user_default") {
        if (isConnected || isConnecting) return
        val normalized = PersonaSettingsCodec.normalizeServerUrl(baseUrl)
        val encodedUser = runCatching { URLEncoder.encode(userId, "UTF-8") }.getOrDefault(userId)
        val wsUrl = normalized.replace("http://", "ws://").replace("https://", "wss://")
            .removeSuffix("/") + "/api/v1/ws/continuous?user_id=$encodedUser"

        isConnecting = true
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnecting = false
                isConnected = true
                Log.d("PersonaWS", "WebSocket connected: $wsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "ready" -> {
                            onReady?.invoke(obj.optString("message", "Ready"))
                        }
                        "interrupt_ack" -> {
                            onInterruptAck?.invoke()
                        }
                        "asr_result" -> {
                            val asrText = obj.optString("text")
                            if (asrText.isNotEmpty()) {
                                onAsrResult?.invoke(asrText)
                            }
                        }
                        "thinking_start" -> {
                            onThinkingStart?.invoke()
                        }
                        "stream_chunk" -> {
                            val delta = obj.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                onStreamChunk?.invoke(delta)
                            }
                        }
                        "error" -> {
                            val errMsg = obj.optString("error").ifBlank { obj.optString("message", "连麦服务异常") }
                            onError?.invoke(errMsg)
                        }
                        "response_complete" -> {
                            val respText = PersonaTextSanitizer.stripControlTags(obj.optString("text"), stripTrailingPartial = false)
                            val emotionStr = obj.optString("emotion", "NEUTRAL")
                            val actionStr = obj.optString("action", "IDLE")
                            val expr = obj.optString("expression", "SMILE")
                            val audioBase64 = obj.optString("audio_base64").takeIf { it.isNotEmpty() }
                            val affinity = obj.optInt("current_affinity", 0)
                            val mood = obj.optString("mood", "陪伴中")

                            val emotion = try { EmotionType.valueOf(emotionStr) } catch (e: Exception) { EmotionType.NEUTRAL }
                            val action = try { ActionType.valueOf(actionStr) } catch (e: Exception) { ActionType.IDLE }

                            onResponseComplete?.invoke(respText, emotion, action, expr, audioBase64, affinity, mood)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PersonaWS", "Error parsing WebSocket message: $e")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnecting = false
                isConnected = false
                if (this@PersonaWebSocketClient.webSocket === webSocket) {
                    this@PersonaWebSocketClient.webSocket = null
                }
                Log.w("PersonaWS", "WebSocket failure: ${t.message}")
                onError?.invoke(t.message ?: "Network error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting = false
                isConnected = false
                if (this@PersonaWebSocketClient.webSocket === webSocket) {
                    this@PersonaWebSocketClient.webSocket = null
                }
                Log.d("PersonaWS", "WebSocket closed: $reason")
                onDisconnected?.invoke(reason.ifBlank { "连接已断开 ($code)" })
            }
        })
    }

    /**
     * 用户发声瞬间触发打断
     */
    fun sendSpeechStart() {
        val payload = JSONObject().apply {
            put("type", "speech_start")
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(payload.toString())
    }

    /**
     * 发送录音音频数据进行全双工 ASR 识别与连麦应答
     */
    fun sendAudioMessage(audioBase64: String, format: String = "wav") {
        val payload = JSONObject().apply {
            put("type", "audio_message")
            put("audio_base64", audioBase64)
            put("audio_format", format)
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(payload.toString())
    }

    /**
     * 发送文本或断句后的对话内容
     */
    fun sendTextMessage(text: String, audioBase64: String? = null) {
        val payload = JSONObject().apply {
            put("type", "text_message")
            put("text", text)
            if (audioBase64 != null) {
                put("audio_base64", audioBase64)
            }
        }
        webSocket?.send(payload.toString())
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        webSocket = null
        isConnected = false
    }
}
