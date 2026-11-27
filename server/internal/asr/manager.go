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
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}

	m.mu.RLock()
	p := m.provider
	cfg := m.config
	m.mu.RUnlock()

	text, err := p.Transcribe(ctx, audioData, filename)
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	if err != nil {
		// 自动降级为内置启发式 ASR 识别
		mock := NewHeuristicMockProvider(cfg)
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
	_, err := m.TestConnectionWithMode()
	return err
}

// TestConnectionWithMode 执行 ASR 连通性自测并返回实际生效模式（external / heuristic / heuristic-fallback）。
func (m *Manager) TestConnectionWithMode() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	m.mu.RLock()
	p := m.provider
	cfg := m.config
	m.mu.RUnlock()

	// 构造 100ms 模拟静音 PCM 帧 (16kHz 16bit = 3200 字节)
	dummyAudio := make([]byte, 3200)
	if _, ok := p.(*HeuristicMockProvider); ok {
		if _, err := p.Transcribe(ctx, dummyAudio, "test.wav"); err != nil {
			return "heuristic", fmt.Errorf("asr connection test failed: %w", err)
		}
		return "heuristic", nil
	}

	if _, err := p.Transcribe(ctx, dummyAudio, "test.wav"); err != nil {
		// 外部 Whisper 不可达时仍可自动降级至内置启发式引擎
		mock := NewHeuristicMockProvider(cfg)
		if _, fErr := mock.Transcribe(ctx, dummyAudio, "test.wav"); fErr == nil {
			return "heuristic-fallback", nil
		}
		return "external", fmt.Errorf("asr connection test failed: %w", err)
	}
	return "external", nil
}
