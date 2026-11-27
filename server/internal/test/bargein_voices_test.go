package test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/ShanWuYinShe/persona_ar/server/internal/asr"
	"github.com/ShanWuYinShe/persona_ar/server/internal/llm"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
	"github.com/ShanWuYinShe/persona_ar/server/internal/tts"
)

func TestPersonaVoicesDistinctFemaleAndMatchStore(t *testing.T) {
	expected := map[string]string{
		"hoshino": "zh-CN-XiaoyiNeural",
		"luna":    "zh-CN-XiaoxiaoNeural",
		"airi":    "zh-TW-HsiaoYuNeural",
		"cyber_k": "zh-TW-HsiaoChenNeural",
	}

	seen := make(map[string]string)
	for id, wantVoice := range expected {
		def, ok := persona.PersonaRegistry[id]
		if !ok {
			t.Fatalf("PersonaRegistry 缺少角色 %s", id)
		}
		if def.Voice != wantVoice {
			t.Errorf("角色 %s 音色应为 %s，实际 %s", id, wantVoice, def.Voice)
		}
		if prevID, dup := seen[def.Voice]; dup {
			t.Errorf("角色 %s 与 %s 使用了重复音色 %s", id, prevID, def.Voice)
		}
		seen[def.Voice] = id
	}

	r := setupTestRouter()
	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/api/v1/store/models", nil)
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("store/models 返回异常: %d", w.Code)
	}

	var models []model.PersonaModelManifest
	if err := json.Unmarshal(w.Body.Bytes(), &models); err != nil {
		t.Fatalf("解析 store/models 失败: %v", err)
	}
	for _, m := range models {
		want, ok := expected[m.ID]
		if !ok {
			continue
		}
		if m.VoiceName != want || m.DefaultVoice != want {
			t.Errorf("商城角色 %s 音色不一致: want=%s got voice_name=%s defaultVoice=%s",
				m.ID, want, m.VoiceName, m.DefaultVoice)
		}
	}
}

func TestNormalizeOpenAIBaseURLPreservesVersionedPaths(t *testing.T) {
	cases := []struct {
		raw  string
		want string
	}{
		{"https://api.deepseek.com", "https://api.deepseek.com/v1"},
		{"https://api.openai.com/", "https://api.openai.com/v1"},
		{"https://api.openai.com/v1", "https://api.openai.com/v1"},
		{"https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/api/paas/v4"},
		{"https://ark.cn-beijing.volces.com/api/v3/", "https://ark.cn-beijing.volces.com/api/v3"},
	}

	for _, tc := range cases {
		if got := llm.NormalizeOpenAIBaseURL(tc.raw); got != tc.want {
			t.Errorf("NormalizeOpenAIBaseURL(%q) = %q, want %q", tc.raw, got, tc.want)
		}
	}
}

func TestSynthesizeContextAbortsImmediately(t *testing.T) {
	var called atomic.Int32
	synthCanceled := make(chan struct{})

	s := tts.New(tts.Options{
		Enabled:    true,
		Voice:      "zh-CN-XiaoyiNeural",
		Provider:   "edge",
		EdgeFormat: "mp3-hq",
		CacheDir:   t.TempDir(),
		Timeout:    5 * time.Second,
		SynthFunc: func(ctx context.Context, _ tts.SynthRequest) ([]byte, error) {
			called.Add(1)
			<-ctx.Done()
			close(synthCanceled)
			return nil, ctx.Err()
		},
	})

	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(20*time.Millisecond, cancel)

	start := time.Now()
	audio := s.SynthesizeContext(ctx, "打断测试语音", "zh-CN-XiaoyiNeural", model.EmotionJoy, 20)
	elapsed := time.Since(start)

	if audio != nil {
		t.Fatalf("上下文被取消后应返回 nil，实际得到 %d 字符", len(*audio))
	}
	if elapsed > 500*time.Millisecond {
		t.Errorf("SynthesizeContext 取消响应过慢: %v", elapsed)
	}
	select {
	case <-synthCanceled:
	case <-time.After(500 * time.Millisecond):
		t.Errorf("唯一等待者取消后，底层 SynthFunc ctx 未随之立即取消")
	}
}

func TestCommitLLMUpdatesMoodAndRespectsPersonaOverride(t *testing.T) {
	origLLM := llm.DefaultManager.GetConfig()
	t.Cleanup(func() {
		llm.DefaultManager.Configure(origLLM)
	})

	mockLLM := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"哼，笨蛋使魔竟然直视本魔女！<emotion:SHY> <action:BLUSH> <expr:BLUSH_FACE>"}}]}`))
	}))
	defer mockLLM.Close()

	llm.DefaultManager.Configure(llm.ProviderConfig{
		Provider: "openai",
		APIKey:   "sk-test-override",
		BaseURL:  mockLLM.URL + "/v1",
		Model:    "mock-model",
	})

	r := setupTestRouter()
	userID := fmt.Sprintf("persona_override_user_%d", time.Now().UnixNano())

	// 用户当前激活角色设为 hoshino，但在单次请求显式传入 persona_id=luna
	payload := map[string]string{
		"user_id":    userID,
		"persona_id": "luna",
		"message":    "露娜今天真可爱",
	}
	data, _ := json.Marshal(payload)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("POST", "/api/v1/chat/interact", bytes.NewBuffer(data))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("chat/interact failed: %d %s", w.Code, w.Body.String())
	}
	var chatResp model.ChatResponse
	if err := json.Unmarshal(w.Body.Bytes(), &chatResp); err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if chatResp.Mood != "害羞心动" {
		t.Errorf("Mood 未随 LLM 情绪标签更新: want 害羞心动 got %q", chatResp.Mood)
	}
	if chatResp.Emotion != model.EmotionShy {
		t.Errorf("Emotion 不匹配: got %s", chatResp.Emotion)
	}

	// 验证 luna 状态已独立更新而 hoshino 状态未混用
	wStatus := httptest.NewRecorder()
	reqStatus, _ := http.NewRequest("GET", "/api/v1/persona/status?user_id="+userID+"&persona_id=luna", nil)
	r.ServeHTTP(wStatus, reqStatus)
	var statusResp map[string]interface{}
	_ = json.Unmarshal(wStatus.Body.Bytes(), &statusResp)
	if statusResp["mood"] != "害羞心动" {
		t.Errorf("luna 持久化状态 mood 异常: %v", statusResp["mood"])
	}

	// 验证 /persona/touch 显式传入 persona_id=cyber_k 立即命中 K-07 台词
	touchPayload, _ := json.Marshal(map[string]string{
		"user_id":    userID,
		"persona_id": "cyber_k",
		"part":       string(model.TouchPartHand),
	})
	wTouch := httptest.NewRecorder()
	reqTouch, _ := http.NewRequest("POST", "/api/v1/persona/touch", bytes.NewBuffer(touchPayload))
	reqTouch.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(wTouch, reqTouch)
	if wTouch.Code != http.StatusOK {
		t.Fatalf("persona/touch failed: %d %s", wTouch.Code, wTouch.Body.String())
	}
	var touchResp model.TouchResponse
	_ = json.Unmarshal(wTouch.Body.Bytes(), &touchResp)
	if !strings.Contains(touchResp.Text, "指挥官") {
		t.Errorf("Touch 显式 persona_id=cyber_k 未返回 K-07 专属台词: %q", touchResp.Text)
	}
}

func TestWSBargeInCancelsSlowStreamAndSuppressesStaleFrames(t *testing.T) {
	origLLM := llm.DefaultManager.GetConfig()
	t.Cleanup(func() {
		llm.DefaultManager.Configure(origLLM)
	})

	var turn1Canceled atomic.Bool
	var turn1Started atomic.Bool
	var reqCount atomic.Int32

	sseServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		idx := reqCount.Add(1)
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "no flusher", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)

		if idx == 1 {
			turn1Started.Store(true)
			_, _ = fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"缓慢的第一句\"}}]}\n\n")
			flusher.Flush()
			select {
			case <-r.Context().Done():
				turn1Canceled.Store(true)
			case <-time.After(4 * time.Second):
			}
			return
		}

		_, _ = fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"即时响应的新回复<emotion:JOY> <action:WAVE> <expr:SMILE>\"}}]}\n\n")
		_, _ = fmt.Fprint(w, "data: [DONE]\n\n")
		flusher.Flush()
	}))
	defer sseServer.Close()

	llm.DefaultManager.Configure(llm.ProviderConfig{
		Provider: "openai",
		APIKey:   "sk-test-bargein",
		BaseURL:  sseServer.URL + "/v1",
		Model:    "mock-stream",
	})

	r := setupTestRouter()
	ts := httptest.NewServer(r)
	defer ts.Close()

	userID := fmt.Sprintf("ws_bargein_test_user_%d", time.Now().UnixNano())
	u, _ := url.Parse(ts.URL)
	u.Scheme = "ws"
	u.Path = "/api/v1/ws/continuous"
	u.RawQuery = "user_id=" + userID + "&persona_id=hoshino"

	conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		t.Fatalf("WS dial 失败: %v", err)
	}
	defer conn.Close()

	readMsg := func(timeout time.Duration) map[string]interface{} {
		_ = conn.SetReadDeadline(time.Now().Add(timeout))
		var msg map[string]interface{}
		if err := conn.ReadJSON(&msg); err != nil {
			t.Fatalf("读取 WS 帧失败: %v", err)
		}
		return msg
	}

	// 消费 ready
	ready := readMsg(time.Second)
	if ready["type"] != "ready" {
		t.Fatalf("首帧应为 ready: %v", ready)
	}

	// 发起第 1 轮慢流式请求
	if err := conn.WriteJSON(map[string]string{
		"type":       "text_message",
		"persona_id": "hoshino",
		"text":       "讲个很长的故事",
	}); err != nil {
		t.Fatalf("发送 text_message 失败: %v", err)
	}

	// 等到第 1 轮首块到达后立刻发送 speech_start
	gotChunk := false
	for !gotChunk {
		m := readMsg(2 * time.Second)
		if m["type"] == "stream_chunk" {
			gotChunk = true
		}
	}

	bargeStart := time.Now()
	if err := conn.WriteJSON(map[string]string{"type": "speech_start"}); err != nil {
		t.Fatalf("发送 speech_start 失败: %v", err)
	}

	// 必须在毫秒级收到 interrupt_ack（不能被第 1 轮阻塞 4 秒）
	ackReceived := false
	for !ackReceived {
		m := readMsg(500 * time.Millisecond)
		if m["type"] == "interrupt_ack" {
			ackReceived = true
		}
	}
	if time.Since(bargeStart) > 400*time.Millisecond {
		t.Errorf("interrupt_ack 被阻塞过久: %v", time.Since(bargeStart))
	}

	// 等待上游第 1 轮 HTTP 请求上下文确实被取消
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) && !turn1Canceled.Load() {
		time.Sleep(10 * time.Millisecond)
	}
	if !turn1Started.Load() || !turn1Canceled.Load() {
		t.Fatalf("第 1 轮流式上游未被取消: started=%v canceled=%v", turn1Started.Load(), turn1Canceled.Load())
	}

	// 立即发起第 2 轮，确认未混入第 1 轮任何残留帧或 response_complete
	if err := conn.WriteJSON(map[string]string{
		"type":       "text_message",
		"persona_id": "hoshino",
		"text":       "打断后的新问题",
	}); err != nil {
		t.Fatalf("发送第 2 轮 text_message 失败: %v", err)
	}

	var completedText string
	var mu sync.Mutex
	for completedText == "" {
		m := readMsg(2 * time.Second)
		msgType, _ := m["type"].(string)
		if msgType == "error" {
			t.Fatalf("不应把打断取消当作 error 推送: %v", m)
		}
		if msgType == "response_complete" {
			txt, _ := m["text"].(string)
			mu.Lock()
			completedText = txt
			mu.Unlock()
		}
	}
	if completedText != "即时响应的新回复" {
		t.Errorf("第 2 轮结果被污染: got %q", completedText)
	}

	// 验证第 1 轮被打断的对话没有污染持久化对话次数（应仅为 1）
	wStatus := httptest.NewRecorder()
	reqStatus, _ := http.NewRequest("GET", "/api/v1/persona/status?user_id="+userID+"&persona_id=hoshino", nil)
	r.ServeHTTP(wStatus, reqStatus)
	var status map[string]interface{}
	_ = json.Unmarshal(wStatus.Body.Bytes(), &status)
	if cnt, _ := status["dialogue_count"].(float64); cnt != 1 {
		t.Errorf("被打断轮次不应计入 dialogue_count，期望 1 实际 %v", cnt)
	}
}

func TestASRTestEndpointReportsMode(t *testing.T) {
	origASR := asr.DefaultManager.GetConfig()
	t.Cleanup(func() {
		asr.DefaultManager.UpdateConfig(origASR)
	})

	r := setupTestRouter()

	asr.DefaultManager.UpdateConfig(asr.Config{Provider: "auto"})
	w1 := httptest.NewRecorder()
	req1, _ := http.NewRequest("POST", "/api/v1/asr/test", nil)
	r.ServeHTTP(w1, req1)
	if w1.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w1.Code)
	}
	var body1 map[string]interface{}
	_ = json.Unmarshal(w1.Body.Bytes(), &body1)
	if body1["mode"] != "heuristic" {
		t.Errorf("expected heuristic mode, got %v", body1["mode"])
	}

	asr.DefaultManager.UpdateConfig(asr.Config{
		Provider: "whisper",
		APIKey:   "sk-unreachable",
		BaseURL:  "http://127.0.0.1:1/v1",
	})
	w2 := httptest.NewRecorder()
	req2, _ := http.NewRequest("POST", "/api/v1/asr/test", nil)
	r.ServeHTTP(w2, req2)
	if w2.Code != http.StatusOK {
		t.Fatalf("expected 200 fallback, got %d", w2.Code)
	}
	var body2 map[string]interface{}
	_ = json.Unmarshal(w2.Body.Bytes(), &body2)
	if body2["mode"] != "heuristic-fallback" {
		t.Errorf("expected heuristic-fallback mode, got %v", body2["mode"])
	}
}
