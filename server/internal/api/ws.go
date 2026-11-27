package api

import (
	"context"
	"encoding/base64"
	"errors"
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
	PersonaID   string      `json:"persona_id,omitempty"`
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

	connCtx, connCancel := context.WithCancel(context.Background())
	var turnWg sync.WaitGroup
	defer func() {
		connCancel()
		turnWg.Wait()
	}()

	userID := c.DefaultQuery("user_id", "user_default")
	queryPid := strings.TrimSpace(c.Query("persona_id"))
	activePid := persona.DefaultEngine.GetActivePersonaID(userID)
	if queryPid != "" {
		if _, ok := persona.PersonaRegistry[queryPid]; ok {
			activePid = queryPid
		}
	}

	var sendMu sync.Mutex
	safeWriteJSON := func(v interface{}) error {
		sendMu.Lock()
		defer sendMu.Unlock()
		return conn.WriteJSON(v)
	}

	var turnMu sync.Mutex
	var activeTurnID uint64
	var activeCancel context.CancelFunc

	cancelActiveTurn := func() {
		turnMu.Lock()
		activeTurnID++
		cancel := activeCancel
		activeCancel = nil
		turnMu.Unlock()
		if cancel != nil {
			cancel()
		}
	}

	startNewTurn := func() (uint64, context.Context, context.CancelFunc) {
		turnMu.Lock()
		prevCancel := activeCancel
		activeTurnID++
		turnID := activeTurnID
		turnCtx, turnCancel := context.WithCancel(connCtx)
		activeCancel = turnCancel
		turnMu.Unlock()
		if prevCancel != nil {
			prevCancel()
		}
		return turnID, turnCtx, turnCancel
	}

	isTurnCurrent := func(turnID uint64, turnCtx context.Context) bool {
		if turnCtx.Err() != nil {
			return false
		}
		turnMu.Lock()
		current := activeTurnID == turnID
		turnMu.Unlock()
		return current
	}

	sendTurnJSON := func(turnID uint64, turnCtx context.Context, v interface{}) bool {
		sendMu.Lock()
		defer sendMu.Unlock()
		if !isTurnCurrent(turnID, turnCtx) {
			return false
		}
		return conn.WriteJSON(v) == nil
	}

	// 1. 发送就绪帧
	_ = safeWriteJSON(gin.H{
		"type":       "ready",
		"message":    "全双工连麦通道已就绪",
		"persona_id": activePid,
	})

	processTextTurn := func(turnID uint64, turnCtx context.Context, userText, personaID string) {
		// 广播思考开始态
		if !sendTurnJSON(turnID, turnCtx, gin.H{
			"type":    "thinking_start",
			"message": "正在思考回应...",
		}) {
			return
		}

		onChunk := func(chunk string) {
			sendTurnJSON(turnID, turnCtx, gin.H{
				"type":  "stream_chunk",
				"delta": chunk,
			})
		}

		resp, err := persona.DefaultEngine.ChatStreamWithPersona(turnCtx, userID, personaID, userText, onChunk)
		if err != nil {
			if errors.Is(err, context.Canceled) || !isTurnCurrent(turnID, turnCtx) {
				return
			}
			sendTurnJSON(turnID, turnCtx, gin.H{
				"type":  "error",
				"error": err.Error(),
			})
			return
		}

		// 下发完整应答包（写入前校验当前轮次未被新语音/barge-in 替代）
		sendTurnJSON(turnID, turnCtx, gin.H{
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

		msgPid := strings.TrimSpace(inMsg.PersonaID)
		if msgPid == "" {
			msgPid = queryPid
		}

		switch inMsg.Type {
		case "speech_start":
			// 立即取消正在进行的 ASR / LLM / TTS，确保全双工毫秒级打断生效
			cancelActiveTurn()
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
			text := inMsg.Text
			turnID, turnCtx, turnCancel := startNewTurn()
			turnWg.Add(1)
			go func() {
				defer turnWg.Done()
				defer turnCancel()
				processTextTurn(turnID, turnCtx, text, msgPid)
			}()

		case "audio_message":
			audioBytes, err := base64.StdEncoding.DecodeString(inMsg.AudioBase64)
			if err != nil || len(audioBytes) == 0 {
				_ = safeWriteJSON(gin.H{
					"type":  "error",
					"error": "无效的音频数据",
				})
				continue
			}

			format := strings.TrimSpace(inMsg.AudioFormat)
			if format == "" {
				format = "wav"
			}

			turnID, turnCtx, turnCancel := startNewTurn()
			turnWg.Add(1)
			go func() {
				defer turnWg.Done()
				defer turnCancel()

				asrCtx, asrCancel := context.WithTimeout(turnCtx, 25*time.Second)
				transcribedText, asrErr := asr.DefaultManager.Transcribe(asrCtx, audioBytes, "ws_voice."+format)
				asrCancel()

				if asrErr != nil {
					if errors.Is(asrErr, context.Canceled) || !isTurnCurrent(turnID, turnCtx) {
						return
					}
					sendTurnJSON(turnID, turnCtx, gin.H{
						"type":  "error",
						"error": "语音识别失败: " + asrErr.Error(),
					})
					return
				}

				if strings.TrimSpace(transcribedText) == "" {
					sendTurnJSON(turnID, turnCtx, gin.H{
						"type":    "asr_empty",
						"message": "未检测到有效语音",
					})
					return
				}

				if !sendTurnJSON(turnID, turnCtx, gin.H{
					"type": "asr_result",
					"text": transcribedText,
				}) {
					return
				}

				processTextTurn(turnID, turnCtx, transcribedText, msgPid)
			}()

		case "ping":
			_ = safeWriteJSON(gin.H{"type": "pong"})
		}
	}
}
