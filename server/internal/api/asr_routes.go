package api

import (
	"context"
	"encoding/base64"
	"io"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/ShanWuYinShe/persona_ar/server/internal/asr"
)

type TranscribeJSONRequest struct {
	AudioBase64 string `json:"audio_base64"`
	Filename    string `json:"filename,omitempty"`
	Format      string `json:"format,omitempty"`
}

func RegisterASRRoutes(rg *gin.RouterGroup) {
	group := rg.Group("/asr")
	{
		group.POST("/transcribe", handleTranscribe)
		group.GET("/config", handleGetASRConfig)
		group.POST("/config", handleUpdateASRConfig)
		group.POST("/test", handleTestASR)
	}
}

func handleTranscribe(c *gin.Context) {
	var audioBytes []byte
	var filename string

	// 1. 优先尝试 multipart/form-data
	file, fileHeader, err := c.Request.FormFile("file")
	if err == nil && file != nil {
		defer file.Close()
		filename = fileHeader.Filename
		audioBytes, _ = io.ReadAll(file)
	} else {
		// 备选表单字段 "audio"
		file, fileHeader, err = c.Request.FormFile("audio")
		if err == nil && file != nil {
			defer file.Close()
			filename = fileHeader.Filename
			audioBytes, _ = io.ReadAll(file)
		}
	}

	// 2. 尝试 JSON base64 接收
	if len(audioBytes) == 0 {
		var req TranscribeJSONRequest
		if err := c.ShouldBindJSON(&req); err == nil && req.AudioBase64 != "" {
			decoded, dErr := base64.StdEncoding.DecodeString(req.AudioBase64)
			if dErr == nil && len(decoded) > 0 {
				audioBytes = decoded
				filename = req.Filename
				if filename == "" {
					if req.Format != "" {
						filename = "audio." + req.Format
					} else {
						filename = "audio.wav"
					}
				}
			}
		}
	}

	if len(audioBytes) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "未提供有效音频数据（支持 multipart file 或 json audio_base64）"})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
	defer cancel()

	text, err := asr.DefaultManager.Transcribe(ctx, audioBytes, filename)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "语音识别失败: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, asr.TranscribeResponse{
		Text:     text,
		Provider: asr.DefaultManager.GetConfig().Provider,
	})
}

func handleGetASRConfig(c *gin.Context) {
	c.JSON(http.StatusOK, asr.DefaultManager.GetConfig())
}

func handleUpdateASRConfig(c *gin.Context) {
	var req asr.Config
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "参数格式错误: " + err.Error()})
		return
	}

	asr.DefaultManager.UpdateConfig(req)
	c.JSON(http.StatusOK, gin.H{
		"message": "ASR 配置已更新",
		"config":  asr.DefaultManager.GetConfig(),
	})
}

func handleTestASR(c *gin.Context) {
	mode, err := asr.DefaultManager.TestConnectionWithMode()
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"status": "error",
			"mode":   mode,
			"error":  err.Error(),
		})
		return
	}

	msg := "ASR 语音识别服务连通性自测通过"
	resp := gin.H{
		"status":  "ok",
		"mode":    mode,
		"message": msg,
	}
	if mode == "heuristic" {
		resp["message"] = "当前处于内置启发式 ASR 模式（无需外部 Whisper Key）"
	} else if mode == "heuristic-fallback" {
		resp["message"] = "外部 Whisper 不可达，已自动降级为内置启发式 ASR 模式"
	}

	c.JSON(http.StatusOK, resp)
}
