package config

import (
	"os"
	"strconv"
)

type Config struct {
	Host        string
	Port        string
	LLMProvider string
	LLMAPIKey   string
	LLMBaseURL  string
	LLMModel    string
	TTSEnabled  bool
	TTSVoice    string
}

var Settings *Config

func init() {
	Settings = LoadConfig()
}

func LoadConfig() *Config {
	host := getEnv("HOST", "0.0.0.0")
	port := getEnv("PORT", "8000")
	llmProvider := getEnv("LLM_PROVIDER", "mock")
	llmAPIKey := getEnv("LLM_API_KEY", "")
	llmBaseURL := getEnv("LLM_BASE_URL", "https://api.openai.com")
	llmModel := getEnv("LLM_MODEL", "gpt-4o-mini")
	ttsEnabled := getEnvBool("TTS_ENABLED", true)
	ttsVoice := getEnv("TTS_VOICE", "zh-CN-XiaoyiNeural")

	return &Config{
		Host:        host,
		Port:        port,
		LLMProvider: llmProvider,
		LLMAPIKey:   llmAPIKey,
		LLMBaseURL:  llmBaseURL,
		LLMModel:    llmModel,
		TTSEnabled:  ttsEnabled,
		TTSVoice:    ttsVoice,
	}
}

func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func getEnvBool(key string, defaultVal bool) bool {
	if val := os.Getenv(key); val != "" {
		b, err := strconv.ParseBool(val)
		if err == nil {
			return b
		}
	}
	return defaultVal
}
