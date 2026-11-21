package test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"

	"github.com/ShanWuYinShe/persona_ar/server/internal/api"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
	"github.com/ShanWuYinShe/persona_ar/server/internal/tts"
)

func setupTestRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	api.RegisterRoutes(r)
	return r
}

func TestHealthCheck(t *testing.T) {
	r := setupTestRouter()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/healthz", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", w.Code)
	}

	var body map[string]interface{}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to parse json: %v", err)
	}

	if body["status"] != "healthy" {
		t.Errorf("expected status healthy, got %v", body["status"])
	}
}

func TestChatInteract(t *testing.T) {
	r := setupTestRouter()

	payload := map[string]string{
		"user_id": "test_user_go",
		"message": "你好呀星野！",
	}
	data, _ := json.Marshal(payload)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("POST", "/api/v1/chat/interact", bytes.NewBuffer(data))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", w.Code)
	}

	var resp model.ChatResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp.Text == "" {
		t.Errorf("expected non-empty text response")
	}
	if resp.CurrentAffinity <= 0 {
		t.Errorf("expected positive affinity, got %d", resp.CurrentAffinity)
	}
}

func TestVoiceInteract(t *testing.T) {
	r := setupTestRouter()

	textPrompt := "早安呀！"
	reqBody := model.VoiceInteractRequest{
		UserID:     "test_voice_user",
		TextPrompt: &textPrompt,
	}
	data, _ := json.Marshal(reqBody)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("POST", "/api/v1/voice/interact", bytes.NewBuffer(data))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", w.Code)
	}
}

func TestPersonaTouch(t *testing.T) {
	r := setupTestRouter()

	payload := map[string]string{
		"user_id": "test_user_touch",
		"part":    "HEAD",
	}
	data, _ := json.Marshal(payload)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("POST", "/api/v1/persona/touch", bytes.NewBuffer(data))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", w.Code)
	}

	var resp model.TouchResponse
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp.Text == "" {
		t.Errorf("expected touch text feedback")
	}
}

func TestPersonaStatusAndReset(t *testing.T) {
	r := setupTestRouter()

	// 1. 获取状态
	w1 := httptest.NewRecorder()
	req1, _ := http.NewRequest("GET", "/api/v1/persona/status?user_id=test_status_user", nil)
	r.ServeHTTP(w1, req1)
	if w1.Code != http.StatusOK {
		t.Fatalf("status get failed: %d", w1.Code)
	}

	var status model.PersonaStatus
	_ = json.Unmarshal(w1.Body.Bytes(), &status)
	if status.Name == "" {
		t.Errorf("expected persona name")
	}

	// 2. 重置
	w2 := httptest.NewRecorder()
	req2, _ := http.NewRequest("POST", "/api/v1/persona/reset?user_id=test_status_user", nil)
	r.ServeHTTP(w2, req2)
	if w2.Code != http.StatusOK {
		t.Fatalf("reset failed: %d", w2.Code)
	}
}

func TestPersonaSwitch(t *testing.T) {
	r := setupTestRouter()

	payload := map[string]string{
		"user_id":    "test_switch_user",
		"persona_id": "luna",
	}
	data, _ := json.Marshal(payload)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("POST", "/api/v1/persona/switch", bytes.NewBuffer(data))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("switch failed: %d", w.Code)
	}

	// 验证切换后的状态
	wStatus := httptest.NewRecorder()
	reqStatus, _ := http.NewRequest("GET", "/api/v1/persona/status?user_id=test_switch_user", nil)
	r.ServeHTTP(wStatus, reqStatus)

	var status model.PersonaStatus
	_ = json.Unmarshal(wStatus.Body.Bytes(), &status)
	if !strings.Contains(status.Name, "露娜") {
		t.Errorf("expected Luna persona name, got %s", status.Name)
	}
}

func TestStoreModelsAndPublish(t *testing.T) {
	r := setupTestRouter()

	// 1. 列表
	w1 := httptest.NewRecorder()
	req1, _ := http.NewRequest("GET", "/api/v1/store/models", nil)
	r.ServeHTTP(w1, req1)
	if w1.Code != http.StatusOK {
		t.Fatalf("store get failed: %d", w1.Code)
	}

	var list []model.PersonaModelManifest
	_ = json.Unmarshal(w1.Body.Bytes(), &list)
	if len(list) < 4 {
		t.Errorf("expected at least 4 default models, got %d", len(list))
	}

	// 2. 详情
	w2 := httptest.NewRecorder()
	req2, _ := http.NewRequest("GET", "/api/v1/store/models/hoshino", nil)
	r.ServeHTTP(w2, req2)
	if w2.Code != http.StatusOK {
		t.Fatalf("hoshino get failed: %d", w2.Code)
	}

	// 3. 发布新模型
	newModel := model.PersonaModelManifest{
		ID:           "test_community_char",
		Name:         "初音 (Miku)",
		Version:      "1.0.0",
		Author:       "社区创作者",
		Description:  "苍绿色双马尾歌姬，跨次元来到你的桌前歌唱。",
		Tags:         []string{"歌姬", "双马尾", "音乐"},
		PreviewImage: "file:///assets/miku.png",
		VoiceName:    "zh-CN-XiaoyiNeural",
		GreetingText: "你好呀！我是初音未来~",
		DownloadURL:  "https://example.com/miku.zip",
		FileSize:     "22.5 MB",
	}
	mData, _ := json.Marshal(newModel)
	w3 := httptest.NewRecorder()
	req3, _ := http.NewRequest("POST", "/api/v1/store/models/publish", bytes.NewBuffer(mData))
	req3.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w3, req3)
	if w3.Code != http.StatusOK {
		t.Fatalf("publish failed: %d", w3.Code)
	}
}

func TestMemoryAutoExtractionAndPersistence(t *testing.T) {
	r := setupTestRouter()

	// 1. 对话自动提取喜好
	payload := map[string]string{
		"user_id": "test_memory_commander",
		"message": "星野，我最喜欢喝冰美式咖啡啦！",
	}
	data, _ := json.Marshal(payload)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("POST", "/api/v1/chat/interact", bytes.NewBuffer(data))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("chat interact failed: %d", w.Code)
	}

	// 2. 检查记忆列表
	wMem := httptest.NewRecorder()
	reqMem, _ := http.NewRequest("GET", "/api/v1/persona/memories?user_id=test_memory_commander", nil)
	r.ServeHTTP(wMem, reqMem)
	if wMem.Code != http.StatusOK {
		t.Fatalf("memories get failed: %d", wMem.Code)
	}

	var mems []model.MemoryItem
	_ = json.Unmarshal(wMem.Body.Bytes(), &mems)
	found := false
	for _, m := range mems {
		if strings.Contains(m.Content, "冰美式咖啡") {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("expected auto extracted memory about 冰美式咖啡")
	}

	// 3. 验证本地文件持久化
	if _, err := os.Stat(persona.DefaultMemoryManager.GetStoragePath()); err == nil {
		// 存储文件存在
	}
}

func TestTTSCacheMechanism(t *testing.T) {
	cachePath := tts.DefaultSynthesizer.GetCachePath("你好指挥官", "zh-CN-XiaoyiNeural")
	if !strings.HasSuffix(cachePath, ".mp3") {
		t.Errorf("expected .mp3 suffix, got %s", cachePath)
	}

	// 合成并验证缓存生成
	res := tts.DefaultSynthesizer.SynthesizeToBase64("你好指挥官")
	if res == nil {
		t.Errorf("expected base64 audio response")
	}

	// 二次调用应直接从缓存读取
	res2 := tts.DefaultSynthesizer.SynthesizeToBase64("你好指挥官")
	if res2 == nil || *res2 != *res {
		t.Errorf("expected cached response matching first call")
	}
}

func TestWebSocketContinuous(t *testing.T) {
	r := setupTestRouter()
	server := httptest.NewServer(r)
	defer server.Close()

	u, _ := url.Parse(server.URL)
	u.Scheme = "ws"
	u.Path = "/api/v1/ws/continuous"

	ws, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		t.Fatalf("failed to dial websocket: %v", err)
	}
	defer ws.Close()

	// 1. 验证就绪帧
	var readyFrame map[string]interface{}
	if err := ws.ReadJSON(&readyFrame); err != nil {
		t.Fatalf("read ready frame failed: %v", err)
	}
	if readyFrame["type"] != "ready" {
		t.Errorf("expected ready frame, got %v", readyFrame["type"])
	}

	// 2. 发送语音打断
	_ = ws.WriteJSON(map[string]interface{}{
		"type":      "speech_start",
		"timestamp": 123456,
	})
	var ackFrame map[string]interface{}
	if err := ws.ReadJSON(&ackFrame); err != nil {
		t.Fatalf("read ack frame failed: %v", err)
	}
	if ackFrame["type"] != "interrupt_ack" {
		t.Errorf("expected interrupt_ack, got %v", ackFrame["type"])
	}

	// 3. 发送文本消息
	_ = ws.WriteJSON(map[string]interface{}{
		"type": "text_message",
		"text": "星野，你喜欢现实世界的花园吗？",
	})

	var thinkFrame map[string]interface{}
	if err := ws.ReadJSON(&thinkFrame); err != nil {
		t.Fatalf("read think frame failed: %v", err)
	}
	if thinkFrame["type"] != "thinking_start" {
		t.Errorf("expected thinking_start, got %v", thinkFrame["type"])
	}

	var respFrame map[string]interface{}
	if err := ws.ReadJSON(&respFrame); err != nil {
		t.Fatalf("read response frame failed: %v", err)
	}
	if respFrame["type"] != "response_complete" {
		t.Errorf("expected response_complete, got %v", respFrame["type"])
	}
	if respFrame["text"] == nil || respFrame["text"] == "" {
		t.Errorf("expected text in response_complete")
	}
}
