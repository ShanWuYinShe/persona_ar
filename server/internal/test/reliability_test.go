package test

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/gorilla/websocket"

	"github.com/ShanWuYinShe/persona_ar/server/internal/llm"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
)

func TestLLMHeuristicRoutingWhenUnconfigured(t *testing.T) {
	defer llm.DefaultManager.Configure(llm.ProviderConfig{Provider: "mock"})
	r := setupTestRouter()

	for _, provider := range []string{"mock", "auto", "builtin", ""} {
		llm.DefaultManager.Configure(llm.ProviderConfig{
			Provider: provider,
			BaseURL:  "https://api.deepseek.com",
			APIKey:   "",
			Model:    "deepseek-chat",
		})
		if !llm.DefaultManager.IsHeuristicMode() {
			t.Fatalf("expected heuristic mode for provider=%q without API key", provider)
		}

		// 验证 /llm/test 不会崩溃并返回 heuristic 模式
		wTest := httptest.NewRecorder()
		reqTest, _ := http.NewRequest("POST", "/api/v1/llm/test", nil)
		r.ServeHTTP(wTest, reqTest)
		if wTest.Code != http.StatusOK {
			t.Fatalf("expected 200 on /llm/test in heuristic mode, got %d: %s", wTest.Code, wTest.Body.String())
		}
		var testBody map[string]interface{}
		_ = json.Unmarshal(wTest.Body.Bytes(), &testBody)
		if testBody["mode"] != "heuristic" {
			t.Errorf("expected mode=heuristic, got %v", testBody["mode"])
		}
	}

	// 验证露娜角色真正走人设启发式应答，且好感度增量不双倍累加
	userID := "test_heuristic_luna_affinity"
	persona.DefaultEngine.ResetUserState(userID)
	if !persona.DefaultEngine.SwitchUserPersona(userID, "luna") {
		t.Fatalf("switch to luna failed")
	}
	before := persona.DefaultEngine.GetUserState(userID, "luna").Affinity
	resp, err := persona.DefaultEngine.Chat(userID, "露娜你真可爱，我好喜欢你")
	if err != nil {
		t.Fatalf("chat failed: %v", err)
	}
	if !strings.Contains(resp.Text, "本魔女") {
		t.Errorf("expected luna persona phrasing, got: %s", resp.Text)
	}
	if resp.Action != model.ActionBlush {
		t.Errorf("expected BLUSH action, got: %s", resp.Action)
	}
	if resp.CurrentAffinity != before+resp.AffinityChange {
		t.Errorf("affinity double-counted: before=%d change=%d current=%d", before, resp.AffinityChange, resp.CurrentAffinity)
	}
}

func TestLLMHistoryRetentionAndTagSanitization(t *testing.T) {
	defer llm.DefaultManager.Configure(llm.ProviderConfig{Provider: "mock"})

	var mu sync.Mutex
	var capturedRequests [][]map[string]interface{}
	turn := 0

	fakeOpenAI := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		var reqBody struct {
			Stream   bool                     `json:"stream"`
			Messages []map[string]interface{} `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		mu.Lock()
		turn++
		curTurn := turn
		capturedRequests = append(capturedRequests, reqBody.Messages)
		mu.Unlock()

		if !reqBody.Stream {
			// 第 1 轮故意返回非法枚举标签，校验清洗后落入安全默认值
			reply := "你好指挥官！我收到了第1句 <emotion:EXCITED_UNKNOWN> <action:FLY_AWAY> <expr:MEGA_SMILE>"
			if curTurn >= 2 {
				reply = "第二轮记忆已连接！<emotion:JOY> <action:WAVE> <expr:SMILE>"
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"choices": []map[string]interface{}{
					{"message": map[string]string{"content": reply}},
				},
			})
			return
		}

		// 流式 SSE 分片：刻意将标签逐字符/跨块撕裂，同时包含合法 "<3"
		w.Header().Set("Content-Type", "text/event-stream")
		chunks := []string{
			"你", "好呀 <3 ", "<emo", "tion:SH", "Y> ", "<action:", "BLUSH>", " <expr:BLUSH_FACE>",
		}
		flusher, _ := w.(http.Flusher)
		for _, c := range chunks {
			payload, _ := json.Marshal(map[string]interface{}{
				"choices": []map[string]interface{}{
					{"delta": map[string]string{"content": c}},
				},
			})
			_, _ = fmt.Fprintf(w, "data: %s\n\n", payload)
			if flusher != nil {
				flusher.Flush()
			}
		}
		_, _ = fmt.Fprint(w, "data: [DONE]\n\n")
		if flusher != nil {
			flusher.Flush()
		}
	}))
	defer fakeOpenAI.Close()

	llm.DefaultManager.Configure(llm.ProviderConfig{
		Provider: "openai",
		BaseURL:  fakeOpenAI.URL,
		APIKey:   "sk-local-test-token",
		Model:    "gpt-test",
	})

	userID := "test_llm_history_user"
	persona.DefaultEngine.ResetUserState(userID)

	resp1, err := persona.DefaultEngine.Chat(userID, "第一轮：记住我的暗号是星光蓝。")
	if err != nil {
		t.Fatalf("turn 1 failed: %v", err)
	}
	if resp1.Emotion != model.EmotionNeutral {
		t.Errorf("invalid emotion should sanitize to NEUTRAL, got %s", resp1.Emotion)
	}
	if resp1.Action != model.ActionIdle {
		t.Errorf("invalid action should sanitize to IDLE, got %s", resp1.Action)
	}
	if resp1.Expression != "SMILE" {
		t.Errorf("invalid expr should sanitize to SMILE, got %s", resp1.Expression)
	}

	resp2, err := persona.DefaultEngine.Chat(userID, "第二轮：我的暗号是什么？")
	if err != nil {
		t.Fatalf("turn 2 failed: %v", err)
	}
	if resp2.Emotion != model.EmotionJoy || resp2.Action != model.ActionWave {
		t.Errorf("expected JOY+WAVE on turn 2, got %s+%s", resp2.Emotion, resp2.Action)
	}

	// 验证第 2 轮发给 OpenAI 的 history 同时包含第 1 轮的 user 与 assistant
	mu.Lock()
	turn2Msgs := capturedRequests[1]
	mu.Unlock()
	var sawUser1, sawAssistant1 bool
	for _, m := range turn2Msgs {
		role, _ := m["role"].(string)
		content, _ := m["content"].(string)
		if role == "user" && strings.Contains(content, "星光蓝") {
			sawUser1 = true
		}
		if role == "assistant" && strings.Contains(content, "第1句") {
			sawAssistant1 = true
		}
	}
	if !sawUser1 || !sawAssistant1 {
		t.Errorf("expected both turn 1 user and assistant in turn 2 history, got: %+v", turn2Msgs)
	}

	// 验证 WebSocket 流式输出完全剔除 <emotion>/<action>/<expr> 标签，且保留 "<3"
	r := setupTestRouter()
	srv := httptest.NewServer(r)
	defer srv.Close()
	u, _ := url.Parse(srv.URL)
	u.Scheme = "ws"
	u.Path = "/api/v1/ws/continuous"
	q := u.Query()
	q.Set("user_id", userID)
	u.RawQuery = q.Encode()

	ws, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		t.Fatalf("ws dial failed: %v", err)
	}
	defer ws.Close()

	var ready map[string]interface{}
	_ = ws.ReadJSON(&ready)
	_ = ws.WriteJSON(map[string]interface{}{"type": "text_message", "text": "流式测试"})

	var streamed strings.Builder
	var finalResp map[string]interface{}
	for i := 0; i < 30; i++ {
		var frame map[string]interface{}
		if err := ws.ReadJSON(&frame); err != nil {
			t.Fatalf("ws read failed: %v", err)
		}
		switch frame["type"] {
		case "stream_chunk":
			delta, _ := frame["delta"].(string)
			streamed.WriteString(delta)
		case "response_complete":
			finalResp = frame
			goto doneWS
		}
	}
doneWS:
	streamText := streamed.String()
	if strings.Contains(streamText, "<emotion") || strings.Contains(streamText, "<action") || strings.Contains(streamText, "<expr") {
		t.Errorf("stream_chunk leaked raw XML tag: %q", streamText)
	}
	if !strings.Contains(streamText, "<3") {
		t.Errorf("stream_chunk lost legitimate '<3': %q", streamText)
	}
	if finalResp == nil || finalResp["emotion"] != "SHY" || finalResp["action"] != "BLUSH" {
		t.Errorf("expected SHY/BLUSH in response_complete, got: %+v", finalResp)
	}
}

func TestStreamTagFilterEdgeCases(t *testing.T) {
	f := &persona.StreamTagFilter{}
	inputs := []string{"喜欢你 ", "<3 ", "1 < 2 ", "<emo", "tion:JOY>", " <act", "ion:WAVE>"}
	var sb strings.Builder
	for _, c := range inputs {
		sb.WriteString(f.Feed(c))
	}
	sb.WriteString(f.Flush())
	got := strings.TrimSpace(sb.String())
	want := "喜欢你 <3 1 < 2"
	if got != want {
		t.Errorf("StreamTagFilter got %q, want %q", got, want)
	}

	// 流尾残留未闭合标签应当被吞掉而不泄露
	f2 := &persona.StreamTagFilter{}
	res := f2.Feed("你好啊") + f2.Feed("<emotion:JO") + f2.Flush()
	if res != "你好啊" {
		t.Errorf("expected trailing partial tag stripped, got %q", res)
	}
}

func TestEngineConcurrencyAndPersistence(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "user_states.json")
	eng := persona.NewEngine(statePath)

	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			uid := fmt.Sprintf("race_user_%d", id%3)
			for i := 0; i < 6; i++ {
				_, _ = eng.Chat(uid, "你好呀")
				_, _ = eng.Touch(uid, model.TouchPartHead)
				_ = eng.GetUserState(uid, "")
			}
		}(g)
	}
	wg.Wait()

	eng.SwitchUserPersona("persist_user", "airi")
	resp, err := eng.Chat("persist_user", "学长早呀")
	if err != nil {
		t.Fatalf("chat failed: %v", err)
	}

	// 重新从磁盘加载，验证状态与活跃角色持久化
	reloaded := persona.NewEngine(statePath)
	if pid := reloaded.GetActivePersonaID("persist_user"); pid != "airi" {
		t.Errorf("expected persisted active persona airi, got %s", pid)
	}
	st := reloaded.GetUserState("persist_user", "airi")
	if st.Affinity != resp.CurrentAffinity || st.DialogueCount != 1 {
		t.Errorf("expected persisted affinity=%d count=1, got affinity=%d count=%d",
			resp.CurrentAffinity, st.Affinity, st.DialogueCount)
	}
}

func TestMemoryEmptyJSONAndUniqueIDs(t *testing.T) {
	r := setupTestRouter()

	wMem := httptest.NewRecorder()
	reqMem, _ := http.NewRequest("GET", "/api/v1/persona/memories?user_id=empty_mem_user_xyz", nil)
	r.ServeHTTP(wMem, reqMem)
	if strings.TrimSpace(wMem.Body.String()) != "[]" {
		t.Errorf("expected JSON [] for empty memories, got %s", wMem.Body.String())
	}

	wStore := httptest.NewRecorder()
	reqStore, _ := http.NewRequest("GET", "/api/v1/store/models?tag=nonexistent_tag_xyz", nil)
	r.ServeHTTP(wStore, reqStore)
	if strings.TrimSpace(wStore.Body.String()) != "[]" {
		t.Errorf("expected JSON [] for empty filtered store models, got %s", wStore.Body.String())
	}

	// 并发同毫秒添加记忆 ID 必须严格唯一
	mgr := persona.NewMemoryManager(filepath.Join(t.TempDir(), "mem.json"))
	ids := make(map[string]bool)
	for i := 0; i < 20; i++ {
		item := mgr.AddMemory("u1", "hoshino", fmt.Sprintf("mem-%d", i), persona.MemoryCategorySharedMoment, 3)
		if ids[item.ID] {
			t.Fatalf("duplicate memory ID generated: %s", item.ID)
		}
		ids[item.ID] = true
	}
}

func TestHealthzMetadataAndRealStatusMemories(t *testing.T) {
	r := setupTestRouter()
	uid := "status_real_mem_user"
	persona.DefaultMemoryManager.AddMemory(uid, "hoshino", "在窗边一起看过晚霞", persona.MemoryCategorySharedMoment, 5)

	wHealth := httptest.NewRecorder()
	reqHealth, _ := http.NewRequest("GET", "/healthz", nil)
	r.ServeHTTP(wHealth, reqHealth)
	var health map[string]interface{}
	_ = json.Unmarshal(wHealth.Body.Bytes(), &health)
	if health["llm_mode"] == nil || health["tts_provider"] == nil || health["version"] == nil {
		t.Errorf("expected healthz metadata fields, got: %+v", health)
	}

	wStatus := httptest.NewRecorder()
	reqStatus, _ := http.NewRequest("GET", "/api/v1/persona/status?user_id="+uid, nil)
	r.ServeHTTP(wStatus, reqStatus)
	var status model.PersonaStatus
	_ = json.Unmarshal(wStatus.Body.Bytes(), &status)
	if len(status.RecentMemories) == 0 || !strings.Contains(status.RecentMemories[0], "晚霞") {
		t.Errorf("expected status recent_memories to reflect stored memory, got: %+v", status.RecentMemories)
	}
}
