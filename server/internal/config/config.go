package config

import (
	"os"
	"strconv"
	"strings"
)

type Config struct {
	Host        string
	Port        string
	LLMProvider string
	LLMAPIKey   string
	LLMBaseURL  string
	LLMModel    string

	// ===== 语音合成（TTS）配置 =====
	// TTSProvider 合成供应商：edge（默认，微软 Edge 在线神经网络语音，免费无需 key）| openai | disabled
	TTSProvider string
	// TTSProxy Edge websocket 连接代理（如 http://127.0.0.1:7890），空=直连（仍会读取进程环境变量代理）
	TTSProxy string
	// TTSEdgeFormat Edge 输出格式：mp3-hq（默认 96kbps）| mp3（48kbps）
	TTSEdgeFormat string
	TTSEnabled    bool
	TTSVoice      string

	// ===== OpenAI 兼容 /audio/speech 配置（可选，TTS_PROVIDER=openai 时生效）=====
	TTSBaseURL     string
	TTSAPIKey      string
	TTSModel       string
	TTSOpenAIVoice string
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
	ttsProvider := strings.ToLower(getEnv("TTS_PROVIDER", "edge"))
	ttsProxy := getEnv("TTS_PROXY", "")
	ttsEdgeFormat := getEnv("TTS_EDGE_FORMAT", "mp3-hq")
	ttsBaseURL := getEnv("TTS_BASE_URL", "https://api.openai.com/v1")
	ttsAPIKey := getEnv("TTS_API_KEY", "")
	ttsModel := getEnv("TTS_MODEL", "tts-1")
	ttsOpenAIVoice := getEnv("TTS_OPENAI_VOICE", "alloy")

	return &Config{
		Host:        host,
		Port:        port,
		LLMProvider: llmProvider,
		LLMAPIKey:   llmAPIKey,
		LLMBaseURL:  llmBaseURL,
		LLMModel:    llmModel,

		TTSProvider:    ttsProvider,
		TTSProxy:       ttsProxy,
		TTSEdgeFormat:  ttsEdgeFormat,
		TTSEnabled:     ttsEnabled,
		TTSVoice:       ttsVoice,
		TTSBaseURL:     ttsBaseURL,
		TTSAPIKey:      ttsAPIKey,
		TTSModel:       ttsModel,
		TTSOpenAIVoice: ttsOpenAIVoice,
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
