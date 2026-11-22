package asr

import (
	"context"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
)

type Manager struct {
	mu       sync.RWMutex
	config   Config
	provider Provider
}

var DefaultManager *Manager

func init() {
	cfg := Config{
		Provider: strings.ToLower(os.Getenv("ASR_PROVIDER")),
		APIKey:   os.Getenv("ASR_API_KEY"),
		BaseURL:  os.Getenv("ASR_BASE_URL"),
		Model:    os.Getenv("ASR_MODEL"),
		Language: os.Getenv("ASR_LANGUAGE"),
	}

	if cfg.Provider == "" {
		if cfg.APIKey != "" {
			cfg.Provider = "whisper"
		} else {
			cfg.Provider = "mock"
		}
	}

	DefaultManager = NewManager(cfg)
}

func NewManager(cfg Config) *Manager {
	m := &Manager{config: cfg}
	m.provider = m.createProvider(cfg)
	return m
}

func (m *Manager) createProvider(cfg Config) Provider {
	switch strings.ToLower(cfg.Provider) {
	case "whisper", "groq", "faster-whisper", "openai":
		if cfg.APIKey == "" && !strings.Contains(cfg.BaseURL, "localhost") && !strings.Contains(cfg.BaseURL, "127.0.0.1") {
			return NewHeuristicMockProvider(cfg)
		}
		return NewWhisperProvider(cfg)
	default:
		return NewHeuristicMockProvider(cfg)
	}
}

func (m *Manager) Transcribe(ctx context.Context, audioData []byte, filename string) (string, error) {
	m.mu.RLock()
	p := m.provider
	m.mu.RUnlock()

	text, err := p.Transcribe(ctx, audioData, filename)
	if err != nil {
		// 自动降级为内置启发式 ASR 识别
		mock := NewHeuristicMockProvider(m.config)
		fallbackText, fErr := mock.Transcribe(ctx, audioData, filename)
		if fErr == nil {
			return fallbackText, nil
		}
		return "", err
	}
	return text, nil
}

func (m *Manager) UpdateConfig(cfg Config) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.config = cfg
	m.provider = m.createProvider(cfg)
}

func (m *Manager) GetConfig() Config {
	m.mu.RLock()
	defer m.mu.RUnlock()

	// 敏感信息遮蔽
	masked := m.config
	if len(masked.APIKey) > 8 {
		masked.APIKey = masked.APIKey[:4] + "...." + masked.APIKey[len(masked.APIKey)-4:]
	} else if len(masked.APIKey) > 0 {
		masked.APIKey = "****"
	}
	return masked
}

func (m *Manager) TestConnection() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// 构造 100ms 模拟静音 PCM 帧 (16kHz 16bit = 3200 字节)
	dummyAudio := make([]byte, 3200)
	_, err := m.Transcribe(ctx, dummyAudio, "test.wav")
	if err != nil {
		return fmt.Errorf("asr connection test failed: %w", err)
	}
	return nil
}
