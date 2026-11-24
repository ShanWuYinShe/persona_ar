package tts

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/ShanWuYinShe/persona_ar/server/internal/config"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
)

// CacheVersion 语音缓存版本号。
//
// 旧实现（v1）把 5 字节假 MP3 帧直接写到 data/tts_cache/<sha256>.mp3，
// Android 端 MediaPlayer 拿到后要么 prepare 失败、要么静默无声，只能回退系统 TTS。
// v2 起改为版本化分层目录 data/tts_cache/v2/<provider>/<format>/：
//   - 旧路径下的假缓存永远不会被读到（版本目录隔离），启动时还会主动清理；
//   - provider / 输出格式切换后不会错误复用另一种编码的音频；
//   - 缓存 key = sha256(voice|emotion|rate|pitch|volume|text)，
//     情绪与韵律不同的同一句话会得到不同文件，不会串音。
const CacheVersion = "v2"

const (
	// MinAudioBytes 小于 2KB 的音频一定不是正常语音（一句中文约 15~100KB，5 字节假帧远低于此值）。
	// 缓存命中与合成结果都按该阈值校验，低于阈值的文件视为损坏并删除重合成。
	MinAudioBytes = 2048
	// defaultSynthTimeout 单次合成总超时：网络抖动时最多阻塞 8 秒，之后返回 nil 由客户端回退端侧 TTS。
	defaultSynthTimeout = 8 * time.Second
	// defaultTTSTimeoutSeconds 交给 edge-tts-go 的 websocket 握手超时（库内单位为秒）。
	defaultTTSTimeoutSeconds = 8
	// maxSynthRunes 送合成的文本上限。LLM 回复 max_tokens=260，正常远低于该值；
	// 超长文本截断到最后一个句末标点，避免一次 TTS 请求占用过长时间。
	maxSynthRunes = 1200
	// fallbackVoice 未配置音色时的默认女声（与 config 默认值保持一致）。
	fallbackVoice = "zh-CN-XiaoyiNeural"
)

var (
	// ErrEmptyAudio 合成器返回空音频（网络失败、服务端空响应等）。
	ErrEmptyAudio = errors.New("tts: 合成返回空音频")
	// ErrAudioTooShort 合成/缓存音频小于 MinAudioBytes，视为损坏或占位假数据。
	ErrAudioTooShort = errors.New("tts: 音频数据过短，疑似损坏或假音频")
)

// SynthRequest 一次真实合成请求：文本已清洗，韵律参数已按情绪/好感度算好。
type SynthRequest struct {
	Text    string
	Voice   string
	Emotion model.EmotionType
	Rate    string // 形如 "+6%"
	Pitch   string // 形如 "+8Hz"
	Volume  string // 形如 "+2%"
}

// SynthFunc 可注入的合成函数：测试注入内存假合成器，生产使用 Edge / OpenAI 兼容实现。
type SynthFunc func(ctx context.Context, req SynthRequest) ([]byte, error)

// OpenAICompatibleConfig OpenAI 兼容 /audio/speech 端点配置（TTS_PROVIDER=openai 时生效）。
type OpenAICompatibleConfig struct {
	BaseURL string
	APIKey  string
	Model   string
	Voice   string
}

// Options 构造合成器参数。测试通过 SynthFunc 注入假合成器，完全不依赖真实网络。
type Options struct {
	Enabled    bool
	Voice      string
	Provider   string // edge | openai | disabled
	Proxy      string // Edge websocket 代理，空=直连（库内仍会读取环境变量代理）
	EdgeFormat string // mp3-hq（默认，96kbps） | mp3（48kbps）
	CacheDir   string // 缓存根目录，实际文件落在 <CacheDir>/v2/<provider>/<format>/ 下
	Timeout    time.Duration
	SynthFunc  SynthFunc
	OpenAI     OpenAICompatibleConfig
}

type inflightCall struct {
	done chan struct{}
	data []byte
	err  error
}

// Synthesizer 线程安全的语音合成器：磁盘缓存 + 并发去重 + 超时保护 + 可注入合成实现。
type Synthesizer struct {
	enabled   bool
	provider  string
	format    string
	proxy     string
	cacheRoot string
	timeout   time.Duration
	openAI    OpenAICompatibleConfig
	synthFn   SynthFunc

	mu    sync.RWMutex // 保护 voice / enabled 等可变字段
	voice string

	inflightMu sync.Mutex
	inflight   map[string]*inflightCall // key: 缓存文件绝对路径，同一句话并发只合成一次
}

// DefaultSynthesizer 全局默认合成器（引擎与 HTTP 接口共用）。
var DefaultSynthesizer *Synthesizer

func init() {
	DefaultSynthesizer = New(Options{
		Enabled:    config.Settings.TTSEnabled,
		Voice:      config.Settings.TTSVoice,
		Provider:   config.Settings.TTSProvider,
		Proxy:      config.Settings.TTSProxy,
		EdgeFormat: config.Settings.TTSEdgeFormat,
		CacheDir:   defaultCacheRoot(),
		OpenAI: OpenAICompatibleConfig{
			BaseURL: config.Settings.TTSBaseURL,
			APIKey:  config.Settings.TTSAPIKey,
			Model:   config.Settings.TTSModel,
			Voice:   config.Settings.TTSOpenAIVoice,
		},
	})
	// 启动时清掉旧版本遗留在缓存根目录的 5 字节假 MP3，避免长期占盘与误导排查
	CleanupLegacyCache(DefaultSynthesizer.CacheRoot())
}

// defaultCacheRoot 缓存根目录沿用旧行为：进程工作目录下的 data/tts_cache。
func defaultCacheRoot() string {
	wd, err := os.Getwd()
	if err != nil {
		return filepath.Join("data", "tts_cache")
	}
	return filepath.Join(wd, "data", "tts_cache")
}

// New 按配置构造合成器；provider 非法/缺 Key 时安全回退 edge。
func New(opts Options) *Synthesizer {
	s := &Synthesizer{
		enabled:   opts.Enabled,
		voice:     strings.TrimSpace(opts.Voice),
		provider:  normalizeProvider(opts.Provider),
		proxy:     strings.TrimSpace(opts.Proxy),
		format:    normalizeFormat(opts.EdgeFormat),
		cacheRoot: strings.TrimSpace(opts.CacheDir),
		timeout:   opts.Timeout,
		openAI:    opts.OpenAI,
		inflight:  make(map[string]*inflightCall),
	}
	if s.voice == "" {
		s.voice = fallbackVoice
	}
	if s.cacheRoot == "" {
		s.cacheRoot = defaultCacheRoot()
	}
	if s.timeout <= 0 {
		s.timeout = defaultSynthTimeout
	}
	if normalizeProviderRaw(opts.Provider) == "disabled" {
		s.enabled = false
	}
	if s.provider == "openai" && strings.TrimSpace(s.openAI.APIKey) == "" {
		// 未配置 API Key 时自动回退 Edge，保证「没有 key 也能听到真人声」
		log.Printf("[TTS] TTS_PROVIDER=openai 但 TTS_API_KEY 为空，自动回退 edge")
		s.provider = "edge"
	}
	if opts.SynthFunc != nil {
		s.synthFn = opts.SynthFunc
	} else {
		switch s.provider {
		case "openai":
			s.synthFn = OpenAISynthFunc(s.openAI)
		default:
			s.synthFn = EdgeSynthFunc(s.proxy, s.format, s.timeout)
		}
	}
	return s
}

// normalizeProviderRaw 返回去掉空白并小写后的原始配置值（用于识别 disabled）。
func normalizeProviderRaw(provider string) string {
	return strings.ToLower(strings.TrimSpace(provider))
}

// normalizeProvider 将配置值收敛为 edge / openai / disabled 三者之一，未知值回退 edge。
func normalizeProvider(provider string) string {
	switch p := normalizeProviderRaw(provider); p {
	case "openai", "edge", "disabled":
		return p
	default:
		if p != "" {
			log.Printf("[TTS] 未知的 TTS_PROVIDER=%q，回退 edge", provider)
		}
		return "edge"
	}
}

// normalizeFormat 校验 Edge 输出格式；响应 audio_format 固定为 mp3，故 webm 不支持。
func normalizeFormat(format string) string {
	switch strings.ToLower(strings.TrimSpace(format)) {
	case "mp3":
		return "mp3"
	case "mp3-hq", "":
		return "mp3-hq"
	case "webm":
		log.Printf("[TTS] TTS_EDGE_FORMAT=webm 与响应声明的 audio_format=mp3 不一致，改用 mp3-hq")
		return "mp3-hq"
	default:
		log.Printf("[TTS] 未知的 TTS_EDGE_FORMAT=%q，回退 mp3-hq", format)
		return "mp3-hq"
	}
}

// Provider 返回最终生效的合成供应商（openai 缺 Key 时已回退为 edge）。
func (s *Synthesizer) Provider() string {
	return s.provider
}

// Enabled 当前是否启用语音合成。
func (s *Synthesizer) Enabled() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.enabled
}

// CacheRoot 缓存根目录（不含版本/供应商层级）。
func (s *Synthesizer) CacheRoot() string {
	return s.cacheRoot
}

// SetVoice 切换默认音色（多角色切换时由调用方显式传 voice 亦可）。
func (s *Synthesizer) SetVoice(v string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if strings.TrimSpace(v) != "" {
		s.voice = strings.TrimSpace(v)
	}
}

// GetVoice 读取默认音色。
func (s *Synthesizer) GetVoice() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.voice
}

// cacheDir 当前生效的版本化缓存目录：<root>/v2/<provider>/<format>/
func (s *Synthesizer) cacheDir() string {
	return filepath.Join(s.cacheRoot, CacheVersion, s.provider, s.format)
}

// CachePath 计算某条文本在指定音色/情绪/好感度下的缓存文件路径。
// 注意：与 Synthesize 一致，内部使用清洗后的文本参与 key，保证画面显示文本与合成文本解耦。
func (s *Synthesizer) CachePath(text, voice string, emotion model.EmotionType, affinity int) string {
	cleaned := CleanText(text)
	if cleaned == "" {
		return ""
	}
	targetVoice := strings.TrimSpace(voice)
	if targetVoice == "" {
		targetVoice = s.GetVoice()
	}
	prosody := ProsodyFor(emotion, affinity)
	return s.cachePath(cleaned, targetVoice, emotion, prosody)
}

// GetCachePath 兼容旧接口：中性情绪 + 好感度 0 下的缓存路径。
func (s *Synthesizer) GetCachePath(text, voice string) string {
	return s.CachePath(text, voice, model.EmotionNeutral, 0)
}

// cachePath 缓存 key = sha256(voice|emotion|rate|pitch|volume|text|openai 身份)。
// 使用 \x00 分隔各字段，避免文本里出现分隔符导致不同输入哈希碰撞。
//
// OpenAI 兼容供应商的实际音色/模型来自全局配置而不是 persona.Voice，切换
// TTS_OPENAI_VOICE / TTS_MODEL / TTS_BASE_URL 后若不参与 key，会错误复用旧音色的缓存。
func (s *Synthesizer) cachePath(cleanedText, voice string, emotion model.EmotionType, prosody Prosody) string {
	hasher := sha256.New()
	fmt.Fprintf(hasher, "%s\x00%s\x00%s\x00%s\x00%s\x00%s",
		voice, string(emotion), prosody.Rate, prosody.Pitch, prosody.Volume, cleanedText)
	if s.provider == "openai" {
		fmt.Fprintf(hasher, "\x00%s\x00%s\x00%s", s.openAI.BaseURL, s.openAI.Model, s.openAI.Voice)
	}
	return filepath.Join(s.cacheDir(), hex.EncodeToString(hasher.Sum(nil))+".mp3")
}

// Synthesize 合成语音并返回 base64；文本会先做 TTS 专用清洗（不影响返回给客户端的显示文本）。
// 失败（未启用/空文本/网络错误/超时/空音频/过短音频）统一返回 nil，绝不返回假音频。
func (s *Synthesizer) Synthesize(text, voice string, emotion model.EmotionType, affinity int) *string {
	if !s.Enabled() {
		return nil
	}
	cleaned := CleanText(text)
	if cleaned == "" {
		return nil
	}
	targetVoice := strings.TrimSpace(voice)
	if targetVoice == "" {
		targetVoice = s.GetVoice()
	}
	prosody := ProsodyFor(emotion, affinity)
	cacheFile := s.cachePath(cleaned, targetVoice, emotion, prosody)

	// 1. 命中缓存直接返回（读到损坏文件会删除并继续走合成）
	if data, ok := s.readCache(cacheFile); ok {
		return encodeAudio(data)
	}

	// 2. 未命中：并发去重 + 总超时保护
	req := SynthRequest{
		Text:    cleaned,
		Voice:   targetVoice,
		Emotion: emotion,
		Rate:    prosody.Rate,
		Pitch:   prosody.Pitch,
		Volume:  prosody.Volume,
	}
	data, err := s.synthesizeOnce(cacheFile, req)
	if err != nil || len(data) == 0 {
		return nil
	}
	return encodeAudio(data)
}

// SynthesizeToBase64 兼容旧接口：默认音色 + 中性情绪。
func (s *Synthesizer) SynthesizeToBase64(text string) *string {
	return s.Synthesize(text, "", model.EmotionNeutral, 0)
}

// SynthesizeWithVoice 兼容旧接口：指定音色 + 中性情绪。
func (s *Synthesizer) SynthesizeWithVoice(text, voice string) *string {
	return s.Synthesize(text, voice, model.EmotionNeutral, 0)
}

func encodeAudio(data []byte) *string {
	encoded := base64.StdEncoding.EncodeToString(data)
	return &encoded
}

// readCache 读取并校验缓存；损坏/过短的文件会被删除，等待重新合成。
func (s *Synthesizer) readCache(path string) ([]byte, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, false
	}
	if !isValidAudio(data) {
		log.Printf("[TTS] 缓存文件损坏(b=%d)，删除后重新合成", len(data))
		_ = os.Remove(path)
		return nil, false
	}
	return data, true
}

// isValidAudio 校验音频是否为真实音频：先看大小，再看文件头（MP3 帧同步/ID3，兼容 Ogg/RIFF/ISO-BMFF）。
func isValidAudio(data []byte) bool {
	if len(data) < MinAudioBytes {
		return false
	}
	if len(data) < 4 {
		return false
	}
	if data[0] == 0xFF && data[1]&0xE0 == 0xE0 { // MP3 帧同步字
		return true
	}
	head := string(data[:4])
	switch head {
	case "ID3\x04", "ID3\x03", "ID3\x02", "OggS", "RIFF":
		return true
	}
	if len(data) >= 8 && string(data[4:8]) == "ftyp" { // MP4/AAC
		return true
	}
	return false
}

// synthesizeOnce 并发去重合成：同一缓存 key 的并发请求只真正调用一次合成函数。
func (s *Synthesizer) synthesizeOnce(cacheFile string, req SynthRequest) ([]byte, error) {
	s.inflightMu.Lock()
	if call, ok := s.inflight[cacheFile]; ok {
		s.inflightMu.Unlock()
		<-call.done
		return call.data, call.err
	}
	call := &inflightCall{done: make(chan struct{})}
	s.inflight[cacheFile] = call
	s.inflightMu.Unlock()

	data, err := s.callSynth(req)
	if err == nil && len(data) > 0 {
		// 写缓存失败不影响本次返回，最多下次重新合成
		if werr := s.writeCache(cacheFile, data); werr != nil {
			log.Printf("[TTS] 写缓存失败 voice=%s err=%v", req.Voice, werr)
		}
	}
	call.data, call.err = data, err
	close(call.done)
	s.inflightMu.Lock()
	delete(s.inflight, cacheFile)
	s.inflightMu.Unlock()
	return data, err
}

// callSynth 带总超时调用注入的合成函数，并统一做空/过短音频校验与脱敏日志（不打印全文）。
func (s *Synthesizer) callSynth(req SynthRequest) ([]byte, error) {
	if s.synthFn == nil {
		return nil, errors.New("tts: 合成函数未初始化")
	}
	ctx, cancel := context.WithTimeout(context.Background(), s.timeout)
	defer cancel()

	type synthResult struct {
		data []byte
		err  error
	}
	// 带缓冲通道：超时返回后 goroutine 仍可无阻塞退出，不会因发送而泄漏
	resultCh := make(chan synthResult, 1)
	start := time.Now()
	go func() {
		data, err := s.synthFn(ctx, req)
		resultCh <- synthResult{data: data, err: err}
	}()

	select {
	case <-ctx.Done():
		log.Printf("[TTS] 合成超时 voice=%s emotion=%s chars=%d elapsed=%s",
			req.Voice, req.Emotion, len([]rune(req.Text)), time.Since(start).Round(time.Millisecond))
		return nil, fmt.Errorf("tts: 合成超时(%s)", s.timeout)
	case res := <-resultCh:
		elapsed := time.Since(start).Round(time.Millisecond)
		if res.err != nil {
			log.Printf("[TTS] 合成失败 voice=%s emotion=%s chars=%d elapsed=%s err=%v",
				req.Voice, req.Emotion, len([]rune(req.Text)), elapsed, res.err)
			return nil, res.err
		}
		if len(res.data) == 0 {
			log.Printf("[TTS] 合成返回空音频 voice=%s emotion=%s chars=%d elapsed=%s",
				req.Voice, req.Emotion, len([]rune(req.Text)), elapsed)
			return nil, ErrEmptyAudio
		}
		if len(res.data) < MinAudioBytes {
			log.Printf("[TTS] 合成音频过短 voice=%s bytes=%d elapsed=%s", req.Voice, len(res.data), elapsed)
			return nil, ErrAudioTooShort
		}
		log.Printf("[TTS] 合成成功 voice=%s emotion=%s chars=%d bytes=%d elapsed=%s",
			req.Voice, req.Emotion, len([]rune(req.Text)), len(res.data), elapsed)
		return res.data, nil
	}
}

// writeCache 原子写缓存：先写同目录临时文件再 rename，避免并发读到半截文件。
func (s *Synthesizer) writeCache(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".tts-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

// CleanupLegacyCache 清理旧版（v1）缓存根目录下的假音频：
// 旧实现把所有缓存平铺在 data/tts_cache/ 下且内容为 5 字节假帧，这里删除根目录中所有小于 MinAudioBytes 的 .mp3。
// 返回删除数量。只处理根目录当层文件，不会误删 v2 子目录里的正常缓存。
func CleanupLegacyCache(root string) int {
	if strings.TrimSpace(root) == "" {
		return 0
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return 0
	}
	removed := 0
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(strings.ToLower(entry.Name()), ".mp3") {
			continue
		}
		info, err := entry.Info()
		if err != nil || info.Size() >= MinAudioBytes {
			continue
		}
		if err := os.Remove(filepath.Join(root, entry.Name())); err == nil {
			removed++
		}
	}
	if removed > 0 {
		log.Printf("[TTS] 已清理 %d 个旧版假语音缓存（< %d 字节）", removed, MinAudioBytes)
	}
	return removed
}
