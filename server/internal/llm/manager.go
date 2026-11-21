package llm

import (
	"context"
	"strings"
	"sync"

	"github.com/ShanWuYinShe/persona_ar/server/internal/config"
)

type Manager struct {
	currentClient Client
	currentConfig ProviderConfig
	mu            sync.RWMutex
}

var DefaultManager *Manager

func init() {
	DefaultManager = NewManager()
}

func NewManager() *Manager {
	cfg := ProviderConfig{
		Provider: config.Settings.LLMProvider,
		BaseURL:  config.Settings.LLMBaseURL,
		APIKey:   config.Settings.LLMAPIKey,
		Model:    config.Settings.LLMModel,
	}

	m := &Manager{
		currentConfig: cfg,
	}
	m.currentClient = m.createClient(cfg)
	return m
}

func (m *Manager) createClient(cfg ProviderConfig) Client {
	provider := strings.ToLower(strings.TrimSpace(cfg.Provider))
	if provider == "mock" || cfg.APIKey == "" && provider != "ollama" {
		return NewMockClient()
	}

	return NewOpenAICompatibleClient(cfg.BaseURL, cfg.APIKey, cfg.Model)
}

func (m *Manager) Configure(cfg ProviderConfig) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.currentConfig = cfg
	m.currentClient = m.createClient(cfg)
}

func (m *Manager) GetClient() Client {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.currentClient
}

func (m *Manager) GetConfig() ProviderConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()
	// 返回副本且脱敏部分 APIKey
	cfg := m.currentConfig
	if len(cfg.APIKey) > 8 {
		cfg.APIKey = cfg.APIKey[:4] + "...." + cfg.APIKey[len(cfg.APIKey)-4:]
	}
	return cfg
}

func (m *Manager) TestConnection(ctx context.Context, cfg ProviderConfig) error {
	client := m.createClient(cfg)
	return client.TestConnection(ctx)
}
