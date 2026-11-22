package asr

import (
	"context"
)

// Provider 定义语音识别核心接口
type Provider interface {
	Name() string
	Transcribe(ctx context.Context, audioData []byte, filename string) (string, error)
}

// Config ASR 全局配置结构体
type Config struct {
	Provider string `json:"provider"` // "whisper", "groq", "faster-whisper", "mock"
	APIKey   string `json:"api_key"`
	BaseURL  string `json:"base_url"`
	Model    string `json:"model"`    // 例如 "whisper-1", "whisper-large-v3", "base"
	Language string `json:"language"` // 例如 "zh", "ja", "en"
}

// TranscribeResponse REST 响应体
type TranscribeResponse struct {
	Text            string  `json:"text"`
	DurationSeconds float64 `json:"duration_seconds,omitempty"`
	Provider        string  `json:"provider"`
}
