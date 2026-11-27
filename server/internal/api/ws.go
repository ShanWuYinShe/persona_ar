package api

import (
	"context"
	"encoding/base64"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"

	"github.com/ShanWuYinShe/persona_ar/server/internal/asr"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // 允许跨域接入
	},
}

type WSIncomingMessage struct {
	Type        string      `json:"type"`
	Timestamp   interface{} `json:"timestamp,omitempty"`
	Text        string      `json:"text,omitempty"`
	AudioBase64 string      `json:"audio_base64,omitempty"`
	AudioFormat string      `json:"audio_format,omitempty"`
}

func RegisterWebSocketRoutes(rg *gin.RouterGroup) {
	rg.GET("/ws/continuous", handleContinuousWebSocket)
}

func handleContinuousWebSocket(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("[WS] Upgrade error: %v", err)
		return
	}
	defer conn.Close()

	userID := c.DefaultQuery("user_id", "user_default")
	activePid := persona.DefaultEngine.GetActivePersonaID(userID)

	var sendMu sync.Mutex
	safeWriteJSON := func(v interface{}) error {
		sendMu.Lock()
		defer sendMu.Unlock()
		return conn.WriteJSON(v)
	}

	// 1. 发送就绪帧
	_ = safeWriteJSON(gin.H{
		"type":       "ready",
		"message":    "全双工连麦通道已就绪",
		"persona_id": activePid,
	})

	processTextMessage := func(userText string) {
		// 广播思考开始态
		_ = safeWriteJSON(gin.H{
			"type":    "thinking_start",
			"message": "正在思考回应...",
		})

		onChunk := func(chunk string) {
			_ = safeWriteJSON(gin.H{
				"type":  "stream_chunk",
				"delta": chunk,
			})
		}

		resp, err := persona.DefaultEngine.ChatStream(userID, userText, onChunk)
		if err != nil {
			_ = safeWriteJSON(gin.H{
				"type":  "error",
				"error": err.Error(),
			})
			return
		}

		// 下发完整应答包
		_ = safeWriteJSON(gin.H{
			"type":             "response_complete",
			"text":             resp.Text,
			"emotion":          resp.Emotion,
			"action":           resp.Action,
			"expression":       resp.Expression,
			"audio_base64":     resp.AudioBase64,
			"audio_format":     resp.AudioFormat,
			"affinity_change":  resp.AffinityChange,
			"current_affinity": resp.CurrentAffinity,
			"mood":             resp.Mood,
		})
	}

	for {
		var inMsg WSIncomingMessage
		if err := conn.ReadJSON(&inMsg); err != nil {
			break
		}

		switch inMsg.Type {
		case "speech_start":
			// 客户端检测到指挥官开始说话，毫秒级即时应答并通知中断当前播报
			_ = safeWriteJSON(gin.H{
				"type":   "interrupt_ack",
				"action": "interrupt_speaking",
			})

		case "text_message":
			if strings.TrimSpace(inMsg.Text) == "" {
				_ = safeWriteJSON(gin.H{
					"type":  "error",
					"error": "消息内容不能为空",
				})
				continue
			}
			processTextMessage(inMsg.Text)

		case "audio_message":
			// 1. 解码音频并进行语音识别
			audioBytes, err := base64.StdEncoding.DecodeString(inMsg.AudioBase64)
			if err != nil || len(audioBytes) == 0 {
				_ = safeWriteJSON(gin.H{
					"type":  "error",
					"error": "无效的音频数据",
				})
				continue
			}

			format := inMsg.AudioFormat
			if format == "" {
				format = "wav"
			}

			asrCtx, asrCancel := context.WithTimeout(context.Background(), 25*time.Second)
			transcribedText, asrErr := asr.DefaultManager.Transcribe(asrCtx, audioBytes, "ws_voice."+format)
			asrCancel()

			if asrErr != nil {
				_ = safeWriteJSON(gin.H{
					"type":  "error",
					"error": "语音识别失败: " + asrErr.Error(),
				})
				continue
			}

			// 2. 广播识别结果给客户端（作为用户发言气泡显示）
			_ = safeWriteJSON(gin.H{
				"type": "asr_result",
				"text": transcribedText,
			})

			// 3. 驱动大模型回复
			processTextMessage(transcribedText)

		case "ping":
			_ = safeWriteJSON(gin.H{"type": "pong"})
		}
	}
}
