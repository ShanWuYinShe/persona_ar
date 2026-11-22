package asr

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"time"
)

// WhisperProvider 实现兼容 OpenAI/Groq/Faster-Whisper 的语音识别
type WhisperProvider struct {
	config Config
	client *http.Client
}

func NewWhisperProvider(cfg Config) *WhisperProvider {
	baseURL := strings.TrimRight(cfg.BaseURL, "/")
	if baseURL == "" {
		baseURL = "https://api.openai.com/v1"
	}
	cfg.BaseURL = baseURL

	if cfg.Model == "" {
		cfg.Model = "whisper-1"
	}

	return &WhisperProvider{
		config: cfg,
		client: &http.Client{
			Timeout: 45 * time.Second,
		},
	}
}

func (p *WhisperProvider) Name() string {
	if p.config.Provider != "" {
		return p.config.Provider
	}
	return "whisper"
}

func (p *WhisperProvider) Transcribe(ctx context.Context, audioData []byte, filename string) (string, error) {
	if len(audioData) == 0 {
		return "", fmt.Errorf("audio data is empty")
	}
	if filename == "" {
		filename = "audio.wav"
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)

	// 1. 写入音频文件字段
	part, err := writer.CreateFormFile("file", filename)
	if err != nil {
		return "", fmt.Errorf("failed to create form file: %w", err)
	}
	if _, err := io.Copy(part, bytes.NewReader(audioData)); err != nil {
		return "", fmt.Errorf("failed to copy audio data: %w", err)
	}

	// 2. 写入模型参数
	if err := writer.WriteField("model", p.config.Model); err != nil {
		return "", fmt.Errorf("failed to write model field: %w", err)
	}

	// 3. 可选语言与提示参数
	if p.config.Language != "" {
		_ = writer.WriteField("language", p.config.Language)
	}
	_ = writer.WriteField("response_format", "json")

	if err := writer.Close(); err != nil {
		return "", fmt.Errorf("failed to close multipart writer: %w", err)
	}

	endpoint := fmt.Sprintf("%s/audio/transcriptions", p.config.BaseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, &body)
	if err != nil {
		return "", fmt.Errorf("failed to build http request: %w", err)
	}

	req.Header.Set("Content-Type", writer.FormDataContentType())
	if p.config.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+p.config.APIKey)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("asr request failed: %w", err)
	}
	defer resp.Body.Close()

	respBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("asr upstream returned status %d: %s", resp.StatusCode, string(respBytes))
	}

	var parsed struct {
		Text  string `json:"text"`
		Error *struct {
			Message string `json:"message"`
		} `json:"error,omitempty"`
	}

	if err := json.Unmarshal(respBytes, &parsed); err != nil {
		return "", fmt.Errorf("failed to parse json response: %w", err)
	}

	if parsed.Error != nil && parsed.Error.Message != "" {
		return "", fmt.Errorf("asr api error: %s", parsed.Error.Message)
	}

	return strings.TrimSpace(parsed.Text), nil
}
