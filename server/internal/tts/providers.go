package tts

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	edge_tts "github.com/wujunwei928/edge-tts-go/edge_tts"
)

// maxResponseBytes 响应体防御性上限：正常一句语音 < 200KB，8MB 足以覆盖长文本，避免异常响应撑爆内存。
const maxResponseBytes = 8 << 20

// EdgeSynthFunc 返回真正调用微软 Edge 在线阅读服务的合成函数（免费、无需 API Key）。
//
// 依赖 github.com/wujunwei928/edge-tts-go 负责 websocket 握手、Sec-MS-GEC DRM 令牌与 SSML 封装，
// 本包不手写任何 websocket/DRM 协议细节。
//
// 注意：库的 Stream() 不接收 context，无法主动取消；总超时由调用方 callSynth 兜底，
// 超时后最多遗留一个等待网络读的 goroutine，连接最终会因 TCP/握手超时释放。
func EdgeSynthFunc(proxy, format string, handshakeTimeout time.Duration) SynthFunc {
	outFormat := edge_tts.OutputFormatMP3HQ
	if format == "mp3" {
		outFormat = edge_tts.OutputFormatMP3
	}
	receiveTimeout := defaultTTSTimeoutSeconds
	if handshakeTimeout > 0 && handshakeTimeout < 60*time.Second {
		receiveTimeout = int(handshakeTimeout / time.Second)
		if receiveTimeout < 1 {
			receiveTimeout = 1
		}
	}
	return func(ctx context.Context, req SynthRequest) ([]byte, error) {
		_ = ctx // 库不支持 context；总超时由调用方控制

		communicate, err := edge_tts.NewCommunicate(req.Text,
			edge_tts.SetVoice(req.Voice),
			edge_tts.SetRate(req.Rate),
			edge_tts.SetPitch(req.Pitch),
			edge_tts.SetVolume(req.Volume),
			edge_tts.SetOutputFormat(outFormat),
			edge_tts.SetReceiveTimeout(receiveTimeout),
			edge_tts.SetProxy(proxy),
		)
		if err != nil {
			return nil, fmt.Errorf("tts: 构造 Edge 合成请求失败: %w", err)
		}

		audio, err := communicate.Stream()
		if err != nil {
			return nil, fmt.Errorf("tts: Edge 合成失败: %w", err)
		}
		return audio, nil
	}
}

// OpenAISynthFunc 返回 OpenAI 兼容 /audio/speech 端点的合成函数（可选供应商）。
//
// 端点本身没有 SSML prosody 参数，情绪韵律仅体现在缓存 key 隔离上；
// 若使用 gpt-4o-mini-tts 一类支持 instructions 的模型，可后续在此追加情绪指令。
func OpenAISynthFunc(cfg OpenAICompatibleConfig) SynthFunc {
	baseURL := strings.TrimRight(strings.TrimSpace(cfg.BaseURL), "/")
	if baseURL == "" {
		baseURL = "https://api.openai.com/v1"
	}
	model := strings.TrimSpace(cfg.Model)
	if model == "" {
		model = "tts-1"
	}
	voice := strings.TrimSpace(cfg.Voice)
	if voice == "" {
		voice = "alloy"
	}
	return func(ctx context.Context, req SynthRequest) ([]byte, error) {
		payload := map[string]string{
			"model":           model,
			"input":           req.Text,
			"voice":           voice,
			"response_format": "mp3",
		}
		body, err := json.Marshal(payload)
		if err != nil {
			return nil, fmt.Errorf("tts: 序列化 OpenAI 语音请求失败: %w", err)
		}

		httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/audio/speech", bytes.NewReader(body))
		if err != nil {
			return nil, fmt.Errorf("tts: 构造 OpenAI 语音请求失败: %w", err)
		}
		httpReq.Header.Set("Content-Type", "application/json")
		httpReq.Header.Set("Authorization", "Bearer "+strings.TrimSpace(cfg.APIKey))

		resp, err := http.DefaultClient.Do(httpReq)
		if err != nil {
			return nil, fmt.Errorf("tts: 请求 OpenAI 语音接口失败: %w", err)
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			// 只回传截断后的响应体，避免日志被大段内容污染
			errBody, _ := io.ReadAll(io.LimitReader(resp.Body, 256))
			return nil, fmt.Errorf("tts: OpenAI 语音接口返回 %d: %s", resp.StatusCode, strings.TrimSpace(string(errBody)))
		}
		audio, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes))
		if err != nil {
			return nil, fmt.Errorf("tts: 读取 OpenAI 语音响应失败: %w", err)
		}
		return audio, nil
	}
}
