package api

import (
	"log"
	"net/http"
	"sync"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"

	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // 允许跨域接入
	},
}

type WSIncomingMessage struct {
	Type      string      `json:"type"`
	Timestamp interface{} `json:"timestamp,omitempty"`
	Text      string      `json:"text,omitempty"`
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

			resp, err := persona.DefaultEngine.ChatStream(userID, inMsg.Text, onChunk)
			if err != nil {
				_ = safeWriteJSON(gin.H{
					"type":  "error",
					"error": err.Error(),
				})
				continue
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

		case "ping":
			_ = safeWriteJSON(gin.H{"type": "pong"})
		}
	}
}
