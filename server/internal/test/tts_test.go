package test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
	"github.com/ShanWuYinShe/persona_ar/server/internal/tts"
)

// 说明：单测禁止访问真实 Edge 网络。
// TestMain 把全局合成器替换为内存假合成器（返回带参数指纹的 4KB 合法 MP3 帧），
// 这样所有走 persona 引擎 / HTTP 接口的用例都不再触发真实合成；
// 需要统计调用次数/注入失败的用例则用 newFakeSynth 单独构造。

// fakeAudioFor 生成带请求指纹的假 MP3 数据：以真实 MP3 帧同步字开头，长度 4KB，
// 足以通过 MinAudioBytes 与文件头校验；指纹里带 voice/emotion/rate/pitch/volume/text，便于断言参数隔离。
func fakeAudioFor(req tts.SynthRequest) []byte {
	fingerprint := fmt.Sprintf("FAKE|%s|%s|%s|%s|%s|%s",
		req.Voice, req.Emotion, req.Rate, req.Pitch, req.Volume, req.Text)
	data := append([]byte{255, 251, 144, 68}, []byte(fingerprint)...)
	if len(data) < 4096 {
		data = append(data, make([]byte, 4096-len(data))...)
	}
	return data
}

func fakeSynthForTest(_ context.Context, req tts.SynthRequest) ([]byte, error) {
	return fakeAudioFor(req), nil
}

func TestMain(m *testing.M) {
	dataRoot, err := os.MkdirTemp("", "persona-server-test-*")
	if err != nil {
		fmt.Fprintf(os.Stderr, "创建测试临时目录失败: %v", err)
		os.Exit(1)
	}
	cacheRoot := filepath.Join(dataRoot, "tts_cache")
	tts.DefaultSynthesizer = tts.New(tts.Options{
		Enabled:    true,
		Voice:      "zh-CN-XiaoyiNeural",
		Provider:   "edge",
		EdgeFormat: "mp3-hq",
		CacheDir:   cacheRoot,
		SynthFunc:  fakeSynthForTest,
	})
	persona.DefaultMemoryManager = persona.NewMemoryManager(filepath.Join(dataRoot, "memories.json"))
	persona.DefaultEngine = persona.NewEngine(filepath.Join(dataRoot, "user_states.json"))
	code := m.Run()
	_ = os.RemoveAll(dataRoot)
	os.Exit(code)
}

// newFakeSynth 构造使用指定缓存目录与假合成实现的合成器；counter 用于断言真实合成调用次数。
func newFakeSynth(t *testing.T, cacheDir string, counter *atomic.Int64) *tts.Synthesizer {
	t.Helper()
	return tts.New(tts.Options{
		Enabled:    true,
		Voice:      "zh-CN-XiaoyiNeural",
		Provider:   "edge",
		EdgeFormat: "mp3-hq",
		CacheDir:   cacheDir,
		SynthFunc: func(_ context.Context, req tts.SynthRequest) ([]byte, error) {
			if counter != nil {
				counter.Add(1)
			}
			return fakeAudioFor(req), nil
		},
	})
}

// decodeAudio 把接口返回的 base64 音频解码为字节，nil 直接判失败。
func decodeAudio(t *testing.T, encoded *string) []byte {
	t.Helper()
	if encoded == nil {
		t.Fatalf("期望非空音频，实际为 nil")
	}
	data, err := base64.StdEncoding.DecodeString(*encoded)
	if err != nil {
		t.Fatalf("base64 解码失败: %v", err)
	}
	return data
}

// countMP3Files 统计目录下所有 .mp3 文件数量（用于断言失败不写缓存）。
func countMP3Files(t *testing.T, root string) int {
	t.Helper()
	count := 0
	err := filepath.Walk(root, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !info.IsDir() && strings.HasSuffix(strings.ToLower(info.Name()), ".mp3") {
			count++
		}
		return nil
	})
	if err != nil {
		t.Fatalf("遍历缓存目录失败: %v", err)
	}
	return count
}

// TestTTSCacheMechanism 缓存命中：同一请求第二次直接读盘，不重复调用合成函数，内容一致。
func TestTTSCacheMechanism(t *testing.T) {
	var calls atomic.Int64
	s := newFakeSynth(t, t.TempDir(), &calls)
	text := "你好指挥官"
	voice := "zh-CN-XiaoyiNeural"

	cachePath := s.GetCachePath(text, voice)
	if !strings.HasSuffix(cachePath, ".mp3") {
		t.Errorf("期望 .mp3 后缀，实际 %s", cachePath)
	}
	// 版本化目录 + 供应商/格式隔离：旧版平铺假缓存不可能被读到
	slashPath := filepath.ToSlash(cachePath)
	if !strings.Contains(slashPath, "/v2/edge/mp3-hq/") {
		t.Errorf("期望版本化缓存路径包含 /v2/edge/mp3-hq/，实际 %s", cachePath)
	}

	first := s.SynthesizeWithVoice(text, voice)
	firstData := decodeAudio(t, first)
	if calls.Load() != 1 {
		t.Fatalf("首次合成应调用合成函数 1 次，实际 %d 次", calls.Load())
	}

	// 二次调用应直接命中磁盘缓存
	second := s.SynthesizeWithVoice(text, voice)
	secondData := decodeAudio(t, second)
	if calls.Load() != 1 {
		t.Errorf("缓存命中不应重复合成，实际调用 %d 次", calls.Load())
	}
	if !bytes.Equal(firstData, secondData) {
		t.Errorf("缓存命中返回内容应与首次合成一致")
	}
	info, err := os.Stat(cachePath)
	if err != nil {
		t.Fatalf("期望缓存文件存在: %v", err)
	}
	if info.Size() < tts.MinAudioBytes {
		t.Errorf("缓存文件大小应 >= %d，实际 %d", tts.MinAudioBytes, info.Size())
	}
}

// TestTTSCacheKeyVariesByEmotion 情绪（韵律）不同 → 缓存 key 不同 → 音频不串用。
func TestTTSCacheKeyVariesByEmotion(t *testing.T) {
	var calls atomic.Int64
	s := newFakeSynth(t, t.TempDir(), &calls)
	text := "今天也要开开心心哦"
	voice := "zh-CN-XiaoxiaoNeural"

	pathJoy := s.CachePath(text, voice, model.EmotionJoy, 50)
	pathSad := s.CachePath(text, voice, model.EmotionSad, 50)
	if pathJoy == pathSad {
		t.Fatalf("不同情绪的缓存 key 不应相同: %s", pathJoy)
	}
	if pathJoy == "" || pathSad == "" {
		t.Fatalf("期望有效缓存路径，实际 joy=%q sad=%q", pathJoy, pathSad)
	}

	joyData := decodeAudio(t, s.Synthesize(text, voice, model.EmotionJoy, 50))
	sadData := decodeAudio(t, s.Synthesize(text, voice, model.EmotionSad, 50))
	if calls.Load() != 2 {
		t.Errorf("两种情绪应各合成一次，实际调用 %d 次", calls.Load())
	}
	if bytes.Equal(joyData, sadData) {
		t.Errorf("JOY 与 SAD 生成的音频不应相同（韵律参数应已注入）")
	}

	// 相同情绪 + 好感度再次调用 → 命中缓存
	_ = s.Synthesize(text, voice, model.EmotionJoy, 50)
	if calls.Load() != 2 {
		t.Errorf("相同情绪/好感度应命中缓存，实际调用 %d 次", calls.Load())
	}
}

// TestTTSCacheKeyVariesByOpenAIConfig OpenAI 兼容供应商的 voice/model/base_url 来自全局配置，
// 切换后必须得到不同的缓存 key，否则用户会听到上一个音色/模型生成的旧音频。
func TestTTSCacheKeyVariesByOpenAIConfig(t *testing.T) {
	dir := t.TempDir()
	pathFor := func(baseURL, modelName, openAIVoice string) string {
		s := tts.New(tts.Options{
			Enabled:    true,
			Voice:      "zh-CN-XiaoyiNeural",
			Provider:   "openai",
			EdgeFormat: "mp3-hq",
			CacheDir:   dir,
			SynthFunc:  fakeSynthForTest,
			OpenAI: tts.OpenAICompatibleConfig{
				BaseURL: baseURL,
				APIKey:  "sk-test-openai-compatible",
				Model:   modelName,
				Voice:   openAIVoice,
			},
		})
		return s.GetCachePath("同一句话", "zh-CN-XiaoyiNeural")
	}

	alloy := pathFor("https://api.openai.com/v1", "tts-1", "alloy")
	nova := pathFor("https://api.openai.com/v1", "tts-1", "nova")
	otherModel := pathFor("https://api.openai.com/v1", "gpt-4o-mini-tts", "alloy")
	otherBase := pathFor("https://example.com/v1", "tts-1", "alloy")
	if alloy == "" {
		t.Fatalf("期望有效缓存路径，实际为空")
	}
	for name, other := range map[string]string{
		"音色":      nova,
		"模型":      otherModel,
		"BaseURL": otherBase,
	} {
		if alloy == other {
			t.Errorf("切换 OpenAI %s 后缓存 key 不应相同: %s", name, alloy)
		}
	}
}

// TestTTSCacheCorruptionAndLegacyInvalidation 覆盖两类历史脏数据：
//  1. v2 缓存文件被破坏成 5 字节假帧 → 命中时校验大小失败，删除并重合成；
//  2. 旧版 v1 平铺在缓存根目录的 5 字节假 mp3 → 永远不会被当成有效命中，启动清理会删除。
func TestTTSCacheCorruptionAndLegacyInvalidation(t *testing.T) {
	var calls atomic.Int64
	root := t.TempDir()
	s := newFakeSynth(t, root, &calls)
	voice := "zh-CN-XiaoyiNeural"

	text := "缓存损坏测试文本"
	cachePath := s.GetCachePath(text, voice)
	if decodeAudio(t, s.SynthesizeWithVoice(text, voice)) == nil {
		t.Fatalf("首次合成失败")
	}
	if calls.Load() != 1 {
		t.Fatalf("首次合成应调用 1 次，实际 %d", calls.Load())
	}

	// 1) 把缓存改写为 5 字节假 MP3 帧
	if err := os.WriteFile(cachePath, []byte{255, 251, 144, 68, 0}, 0o644); err != nil {
		t.Fatalf("写入损坏缓存失败: %v", err)
	}
	regenerated := decodeAudio(t, s.SynthesizeWithVoice(text, voice))
	if calls.Load() != 2 {
		t.Errorf("损坏缓存应触发重合成，实际调用 %d 次", calls.Load())
	}
	if len(regenerated) < tts.MinAudioBytes {
		t.Errorf("重合成音频应 >= %d 字节，实际 %d", tts.MinAudioBytes, len(regenerated))
	}
	info, err := os.Stat(cachePath)
	if err != nil || info.Size() < tts.MinAudioBytes {
		t.Errorf("重新写入的缓存应 >= %d 字节，实际 stat=%v size=%d", tts.MinAudioBytes, err, func() int64 {
			if info == nil {
				return 0
			}
			return info.Size()
		}())
	}

	// 2) 旧版 v1 缓存：key = sha256(voice + "_" + text)，平铺在缓存根目录
	legacyText := "旧假缓存文本"
	hasher := sha256.New()
	hasher.Write([]byte(voice + "_" + legacyText))
	legacyPath := filepath.Join(root, hex.EncodeToString(hasher.Sum(nil))+".mp3")
	if err := os.WriteFile(legacyPath, []byte{255, 251, 144, 68, 0}, 0o644); err != nil {
		t.Fatalf("写入旧版假缓存失败: %v", err)
	}
	legacyAudio := decodeAudio(t, s.SynthesizeWithVoice(legacyText, voice))
	if calls.Load() != 3 {
		t.Errorf("旧版假缓存不应被当作命中，实际调用 %d 次", calls.Load())
	}
	if len(legacyAudio) < tts.MinAudioBytes {
		t.Errorf("旧版假缓存文本应重新合成出真实长度音频，实际 %d 字节", len(legacyAudio))
	}

	// 3) 启动清理只删根目录小文件，不动 v2 里正常缓存
	removed := tts.CleanupLegacyCache(root)
	if removed != 1 {
		t.Errorf("期望清理 1 个旧版假缓存，实际 %d 个", removed)
	}
	if _, err := os.Stat(legacyPath); !os.IsNotExist(err) {
		t.Errorf("旧版假缓存应已被删除，stat err=%v", err)
	}
	if _, err := os.Stat(cachePath); err != nil {
		t.Errorf("v2 正常缓存不应被清理: %v", err)
	}
}

// TestTTSSynthesisFailureReturnsNil 失败路径必须返回 nil（不返回假音频、不写缓存）。
func TestTTSSynthesisFailureReturnsNil(t *testing.T) {
	voice := "zh-CN-XiaoyiNeural"
	cases := []struct {
		name    string
		synthFn tts.SynthFunc
		text    string
	}{
		{"网络错误", func(_ context.Context, _ tts.SynthRequest) ([]byte, error) {
			return nil, errors.New("dial tcp: i/o timeout")
		}, "一条会失败的语音"},
		{"空音频", func(_ context.Context, _ tts.SynthRequest) ([]byte, error) {
			return []byte{}, nil
		}, "空音频测试"},
		{"过短假音频", func(_ context.Context, _ tts.SynthRequest) ([]byte, error) {
			return []byte{255, 251, 144, 68, 0}, nil
		}, "假音频测试"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			var calls atomic.Int64
			s := tts.New(tts.Options{
				Enabled:    true,
				Voice:      voice,
				Provider:   "edge",
				EdgeFormat: "mp3-hq",
				CacheDir:   dir,
				SynthFunc: func(ctx context.Context, req tts.SynthRequest) ([]byte, error) {
					calls.Add(1)
					return tc.synthFn(ctx, req)
				},
			})
			if got := s.SynthesizeWithVoice(tc.text, voice); got != nil {
				t.Errorf("合成失败应返回 nil，实际非空")
			}
			if calls.Load() != 1 {
				t.Errorf("期望调用合成函数 1 次，实际 %d", calls.Load())
			}
			if files := countMP3Files(t, dir); files != 0 {
				t.Errorf("合成失败不应写缓存，实际有 %d 个 mp3 文件", files)
			}
		})
	}

	// 空文本 / 未启用 / provider=disabled 直接返回 nil
	s := newFakeSynth(t, t.TempDir(), nil)
	if got := s.SynthesizeWithVoice("   ", voice); got != nil {
		t.Errorf("空文本应返回 nil")
	}
	if got := s.SynthesizeWithVoice("……！！！", voice); got != nil {
		t.Errorf("纯标点清洗后为空，应返回 nil")
	}
	disabled := tts.New(tts.Options{Enabled: false, Voice: voice, Provider: "edge", CacheDir: t.TempDir(), SynthFunc: fakeSynthForTest})
	if got := disabled.SynthesizeWithVoice("你好", voice); got != nil {
		t.Errorf("TTS 未启用时应返回 nil")
	}
}

// TestTTSConcurrentSynthesisDedup 同文本并发合成只真正调用一次（singleflight 去重）。
func TestTTSConcurrentSynthesisDedup(t *testing.T) {
	var calls atomic.Int64
	gate := make(chan struct{})
	started := make(chan struct{})
	var once sync.Once
	s := tts.New(tts.Options{
		Enabled:    true,
		Voice:      "zh-CN-XiaoyiNeural",
		Provider:   "edge",
		EdgeFormat: "mp3-hq",
		CacheDir:   t.TempDir(),
		SynthFunc: func(_ context.Context, req tts.SynthRequest) ([]byte, error) {
			calls.Add(1)
			once.Do(func() { close(started) })
			<-gate
			return fakeAudioFor(req), nil
		},
	})

	const goroutines = 8
	results := make([]*string, goroutines)
	var wg sync.WaitGroup
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			results[idx] = s.SynthesizeWithVoice("并发同一句话", "zh-CN-XiaoyiNeural")
		}(i)
	}
	<-started
	time.Sleep(30 * time.Millisecond) // 让其余 goroutine 进入 inflight 等待
	close(gate)
	wg.Wait()

	if calls.Load() != 1 {
		t.Errorf("同一文本并发合成应只调用 1 次，实际 %d 次", calls.Load())
	}
	for i, res := range results {
		if res == nil {
			t.Errorf("第 %d 个并发请求返回 nil", i)
		}
	}
}

// TestTTSEmotionAndAffinityProsody 情绪 + 好感度 → 韵律参数表生效且互相隔离。
func TestTTSEmotionAndAffinityProsody(t *testing.T) {
	var mu sync.Mutex
	captured := make([]tts.SynthRequest, 0, 3)
	s := tts.New(tts.Options{
		Enabled:    true,
		Voice:      "zh-CN-XiaoyiNeural",
		Provider:   "edge",
		EdgeFormat: "mp3-hq",
		CacheDir:   t.TempDir(),
		SynthFunc: func(_ context.Context, req tts.SynthRequest) ([]byte, error) {
			mu.Lock()
			captured = append(captured, req)
			mu.Unlock()
			return fakeAudioFor(req), nil
		},
	})
	voice := "zh-CN-XiaoyiNeural"
	text := "同一句话"
	s.Synthesize(text, voice, model.EmotionJoy, 10)
	s.Synthesize(text, voice, model.EmotionSad, 10)
	s.Synthesize(text, voice, model.EmotionJoy, 100)

	if len(captured) != 3 {
		t.Fatalf("期望 3 次合成调用，实际 %d 次", len(captured))
	}
	joyReq, sadReq, joyHighReq := captured[0], captured[1], captured[2]
	if joyReq.Rate == sadReq.Rate && joyReq.Pitch == sadReq.Pitch {
		t.Errorf("JOY 与 SAD 的韵律参数不应相同: joy=%+v sad=%+v", joyReq, sadReq)
	}
	if joyReq.Rate == joyHighReq.Rate {
		t.Errorf("好感度 10 与 100 的语速应有细微差异: %s vs %s", joyReq.Rate, joyHighReq.Rate)
	}
	// 合成函数收到的必须是清洗后的文本
	if joyReq.Text != text {
		t.Errorf("合成文本应经清洗，实际 %q", joyReq.Text)
	}

	// 韵律表关键值：慵懒明显放慢压低，喜悦略微加快上扬
	sleepy := tts.ProsodyFor(model.EmotionSleepy, 0)
	if sleepy.Rate != "-18%" || sleepy.Pitch != "-10Hz" || sleepy.Volume != "-10%" {
		t.Errorf("SLEEPY 韵律与设计表不一致: %+v", sleepy)
	}
	joy := tts.ProsodyFor(model.EmotionJoy, 0)
	if joy.Rate != "+6%" || joy.Pitch != "+8Hz" {
		t.Errorf("JOY 韵律与设计表不一致: %+v", joy)
	}
	// 好感度满级在情绪基础上叠加 +3% 语速、+3Hz 音高
	joyMax := tts.ProsodyFor(model.EmotionJoy, 100)
	if joyMax.Rate != "+9%" || joyMax.Pitch != "+11Hz" {
		t.Errorf("好感度加成不符合设计: %+v", joyMax)
	}
	// 未知名情绪回退中性
	unknown := tts.ProsodyFor(model.EmotionType("UNKNOWN"), 0)
	if unknown != (tts.Prosody{Rate: "+0%", Pitch: "+0Hz", Volume: "+0%"}) {
		t.Errorf("未知情绪应回退中性韵律: %+v", unknown)
	}
}

// TestCleanTextForTTS TTS 文本清洗：只影响合成输入，涉及 emoji/舞台说明/波浪号/省略号/空白等。
func TestCleanTextForTTS(t *testing.T) {
	cases := []struct {
		name     string
		input    string
		contains []string
		absent   []string
		empty    bool
	}{
		{
			name:     "去 emoji",
			input:    "哼哼，现实世界的信号终于连上啦！✨🎉",
			contains: []string{"哼哼", "现实世界的信号终于连上啦", "！"},
			absent:   []string{"✨", "🎉"},
		},
		{
			name:     "去括号舞台说明",
			input:    "（对讲机传来指挥官温和的呼吸与声音）你好呀",
			contains: []string{"你好呀"},
			absent:   []string{"对讲机", "传来"},
		},
		{
			name:     "去情绪标签",
			input:    "<emotion:JOY>真的吗？",
			contains: []string{"真的吗"},
			absent:   []string{"emotion", "JOY"},
		},
		{
			name:     "去颜文字",
			input:    "(｡･ω･｡)今天好开心",
			contains: []string{"今天好开心"},
			absent:   []string{"｡", "ω"},
		},
		{
			name:     "波浪号转停顿",
			input:    "晚安~明天见～",
			contains: []string{"晚安", "明天见", "，"},
			absent:   []string{"~", "～"},
		},
		{
			name:     "省略号归一",
			input:    "我……我没事……",
			contains: []string{"我", "没事"},
			absent:   []string{"……"},
		},
		{
			name:     "压缩空白",
			input:    "Hello   world  你好",
			contains: []string{"Hello world", "你好"},
		},
		{
			name:     "保留英文数字",
			input:    "BGM 是 8bit 风格",
			contains: []string{"BGM", "8bit"},
		},
		{
			name:     "保留有语义的括号",
			input:    "今天（真的）好开心",
			contains: []string{"真的"},
		},
		{
			name:  "纯表情返回空",
			input: "✨✨✨",
			empty: true,
		},
		{
			name:  "纯标点返回空",
			input: "……！！！",
			empty: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := tts.CleanText(tc.input)
			if tc.empty {
				if got != "" {
					t.Errorf("期望清洗结果为空，实际 %q", got)
				}
				return
			}
			if got == "" {
				t.Fatalf("清洗结果不应为空")
			}
			for _, want := range tc.contains {
				if !strings.Contains(got, want) {
					t.Errorf("清洗结果 %q 应包含 %q", got, want)
				}
			}
			for _, forbidden := range tc.absent {
				if strings.Contains(got, forbidden) {
					t.Errorf("清洗结果 %q 不应包含 %q", got, forbidden)
				}
			}
		})
	}
}

// TestTTSProviderFallback 供应商配置：openai 缺 key 回退 edge、disabled 关闭、未知值回退 edge。
func TestTTSProviderFallback(t *testing.T) {
	fakeSynth := func(_ context.Context, req tts.SynthRequest) ([]byte, error) {
		return fakeAudioFor(req), nil
	}
	// openai 未配 API Key → 自动回退 edge（免费可用优先）
	openaiNoKey := tts.New(tts.Options{
		Enabled: true, Voice: "zh-CN-XiaoyiNeural", Provider: "openai",
		EdgeFormat: "mp3-hq", CacheDir: t.TempDir(), SynthFunc: fakeSynth,
	})
	if openaiNoKey.Provider() != "edge" {
		t.Errorf("openai 缺 TTS_API_KEY 应回退 edge，实际 %s", openaiNoKey.Provider())
	}
	// disabled → 不合成
	disabled := tts.New(tts.Options{
		Enabled: true, Voice: "zh-CN-XiaoyiNeural", Provider: "disabled",
		EdgeFormat: "mp3-hq", CacheDir: t.TempDir(), SynthFunc: fakeSynth,
	})
	if disabled.Enabled() {
		t.Errorf("TTS_PROVIDER=disabled 应关闭合成")
	}
	if got := disabled.SynthesizeWithVoice("你好", "zh-CN-XiaoyiNeural"); got != nil {
		t.Errorf("disabled 状态应返回 nil")
	}
	// 未知供应商 → 回退 edge
	unknown := tts.New(tts.Options{
		Enabled: true, Voice: "zh-CN-XiaoyiNeural", Provider: "azure",
		EdgeFormat: "mp3-hq", CacheDir: t.TempDir(), SynthFunc: fakeSynth,
	})
	if unknown.Provider() != "edge" {
		t.Errorf("未知供应商应回退 edge，实际 %s", unknown.Provider())
	}
	// 非法输出格式 → 回退 mp3-hq（通过缓存路径目录体现）
	badFormat := tts.New(tts.Options{
		Enabled: true, Voice: "zh-CN-XiaoyiNeural", Provider: "edge",
		EdgeFormat: "webm", CacheDir: t.TempDir(), SynthFunc: fakeSynth,
	})
	badPath := filepath.ToSlash(badFormat.GetCachePath("你好", "zh-CN-XiaoyiNeural"))
	if !strings.Contains(badPath, "/edge/mp3-hq/") {
		t.Errorf("webm 应回退为 mp3-hq，实际缓存路径 %s", badPath)
	}
}

// TestTTSSynthesisTimeout 总超时保护：合成函数卡住时约 8 秒（测试用 30ms）后返回 nil，不阻塞接口。
func TestTTSSynthesisTimeout(t *testing.T) {
	block := make(chan struct{})
	defer close(block) // 测试结束后释放后台 goroutine
	voice := "zh-CN-XiaoyiNeural"
	s := tts.New(tts.Options{
		Enabled:    true,
		Voice:      voice,
		Provider:   "edge",
		EdgeFormat: "mp3-hq",
		CacheDir:   t.TempDir(),
		Timeout:    30 * time.Millisecond,
		SynthFunc: func(_ context.Context, _ tts.SynthRequest) ([]byte, error) {
			<-block
			return []byte{255, 251, 144, 68, 0}, nil
		},
	})
	start := time.Now()
	if got := s.SynthesizeWithVoice("超时测试文本", voice); got != nil {
		t.Errorf("合成超时应返回 nil，实际非空")
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Errorf("超时保护未及时生效，耗时 %s", elapsed)
	}
}

// TestEdgeTTSSmoke 真实网络冒烟（默认跳过）：验证 Edge 合成链路真实可用。
// 开启方式：RUN_EDGE_TTS_SMOKE=1 cd server && go test -run TestEdgeTTSSmoke -v ./internal/test/
func TestEdgeTTSSmoke(t *testing.T) {
	if os.Getenv("RUN_EDGE_TTS_SMOKE") != "1" {
		t.Skip("真实 Edge 网络冒烟默认跳过；设置 RUN_EDGE_TTS_SMOKE=1 开启")
	}
	cases := []struct {
		voice   string
		emotion model.EmotionType
		text    string
	}{
		{"zh-CN-XiaoyiNeural", model.EmotionJoy, "嘿嘿，指挥官终于来看我啦！今天也要开开心心哦。"},
		{"zh-CN-XiaoxiaoNeural", model.EmotionShy, "学长这么夸我，艾莉心跳都要加速了呢……"},
		{"zh-CN-XiaoyiNeural", model.EmotionSleepy, "呼啊……好困哦，让我在你桌上眯一小会儿嘛。"},
	}
	for idx, tc := range cases {
		s := tts.New(tts.Options{
			Enabled:    true,
			Voice:      tc.voice,
			Provider:   "edge",
			EdgeFormat: "mp3-hq",
			Proxy:      os.Getenv("TTS_PROXY"),
			CacheDir:   t.TempDir(),
		})
		res := s.Synthesize(tc.text, tc.voice, tc.emotion, 20)
		if res == nil {
			t.Fatalf("第 %d 段真实合成失败（检查网络/代理）", idx+1)
		}
		data := decodeAudio(t, res)
		if len(data) < tts.MinAudioBytes {
			t.Fatalf("第 %d 段音频过短: %d 字节", idx+1, len(data))
		}
		if !(data[0] == 255 && data[1]&0xE0 == 0xE0) {
			t.Errorf("第 %d 段不是 MP3 帧数据: %x", idx+1, data[:4])
		}
		t.Logf("真实 Edge 合成 #%d voice=%s emotion=%s bytes=%d", idx+1, tc.voice, tc.emotion, len(data))
	}
}
