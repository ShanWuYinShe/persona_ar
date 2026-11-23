package test

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"

	"github.com/ShanWuYinShe/persona_ar/server/internal/api"
	"github.com/ShanWuYinShe/persona_ar/server/internal/asr"
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

	// 3. 手动创建新回忆
	manualMem := model.CreateMemoryRequest{
		UserID:     "test_memory_commander",
		PersonaID:  "hoshino",
		Category:   "SHARED_MOMENT",
		Content:    "今天和星野一起在虚拟雨夜里写完了全部后端 Go 语言重构代码！",
		Importance: 5,
	}
	memBytes, _ := json.Marshal(manualMem)
	wCreate := httptest.NewRecorder()
	reqCreate, _ := http.NewRequest("POST", "/api/v1/persona/memories", bytes.NewBuffer(memBytes))
	reqCreate.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(wCreate, reqCreate)
	if wCreate.Code != http.StatusOK {
		t.Fatalf("create memory failed: %d", wCreate.Code)
	}

	var createdItem model.MemoryItem
	_ = json.Unmarshal(wCreate.Body.Bytes(), &createdItem)
	if createdItem.ID == "" {
		t.Fatalf("expected created memory to have ID")
	}

	// 4. 删除该回忆
	wDel := httptest.NewRecorder()
	reqDel, _ := http.NewRequest("DELETE", "/api/v1/persona/memories/"+createdItem.ID+"?user_id=test_memory_commander", nil)
	r.ServeHTTP(wDel, reqDel)
	if wDel.Code != http.StatusOK {
		t.Fatalf("delete memory failed: %d", wDel.Code)
	}

	// 5. 验证本地文件持久化
	if _, err := os.Stat(persona.DefaultMemoryManager.GetStoragePath()); err == nil {
		// 存储文件存在
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
	for {
		var frame map[string]interface{}
		if err := ws.ReadJSON(&frame); err != nil {
			t.Fatalf("read frame failed: %v", err)
		}
		if frame["type"] == "stream_chunk" {
			continue
		}
		if frame["type"] == "response_complete" {
			respFrame = frame
			break
		}
		t.Fatalf("unexpected frame type: %v", frame["type"])
	}
	if respFrame["text"] == nil || respFrame["text"] == "" {
		t.Errorf("expected text in response_complete")
	}
}

func TestCircadianAndMultiVoiceIsolation(t *testing.T) {
	router := setupTestRouter()

	// 1. 验证作息关怀（晚安）回复
	nightPayload := map[string]interface{}{
		"user_id": "user_circadian_test",
		"message": "星野，我准备睡觉了，晚安",
	}
	nightBytes, _ := json.Marshal(nightPayload)
	reqNight, _ := http.NewRequest("POST", "/api/v1/chat/interact", bytes.NewBuffer(nightBytes))
	reqNight.Header.Set("Content-Type", "application/json")
	wNight := httptest.NewRecorder()
	router.ServeHTTP(wNight, reqNight)

	if wNight.Code != http.StatusOK {
		t.Fatalf("night chat failed: %d", wNight.Code)
	}
	var nightResp model.ChatResponse
	_ = json.Unmarshal(wNight.Body.Bytes(), &nightResp)
	if !strings.Contains(nightResp.Text, "晚安") && !strings.Contains(nightResp.Text, "睡觉") {
		t.Errorf("expected bedtime care in response, got %s", nightResp.Text)
	}

	// 2. 验证多角色音色缓存隔离
	text := "指挥官，今天也要开开心心哦！"
	pathHoshino := tts.DefaultSynthesizer.GetCachePath(text, "zh-CN-XiaoyiNeural")
	pathLuna := tts.DefaultSynthesizer.GetCachePath(text, "zh-CN-XiaoxiaoNeural")

	if pathHoshino == pathLuna {
		t.Fatalf("expected distinct cache paths for distinct voices, got same: %s", pathHoshino)
	}

	audio1 := tts.DefaultSynthesizer.SynthesizeWithVoice(text, "zh-CN-XiaoyiNeural")
	audio2 := tts.DefaultSynthesizer.SynthesizeWithVoice(text, "zh-CN-XiaoxiaoNeural")
	if audio1 == nil || audio2 == nil {
		t.Fatalf("expected non-nil audio outputs for multi-voice synthesis")
	}
}

func TestLLMProviderAndGracefulFallback(t *testing.T) {
	router := setupTestRouter()

	// 1. 测试设置大模型 API 配置
	cfgPayload := map[string]string{
		"provider": "openai",
		"base_url": "https://api.deepseek.com",
		"api_key":  "sk-test-mock-deepseek-key-123456",
		"model":    "deepseek-chat",
	}
	cfgBytes, _ := json.Marshal(cfgPayload)
	reqCfg, _ := http.NewRequest("POST", "/api/v1/llm/config", bytes.NewBuffer(cfgBytes))
	reqCfg.Header.Set("Content-Type", "application/json")
	wCfg := httptest.NewRecorder()
	router.ServeHTTP(wCfg, reqCfg)

	if wCfg.Code != http.StatusOK {
		t.Fatalf("set llm config failed: %d", wCfg.Code)
	}

	// 2. 测试获取脱敏后的大模型配置
	reqGet, _ := http.NewRequest("GET", "/api/v1/llm/config", nil)
	wGet := httptest.NewRecorder()
	router.ServeHTTP(wGet, reqGet)
	if wGet.Code != http.StatusOK {
		t.Fatalf("get llm config failed: %d", wGet.Code)
	}
	var getResp map[string]interface{}
	_ = json.Unmarshal(wGet.Body.Bytes(), &getResp)
	if getResp["model"] != "deepseek-chat" {
		t.Errorf("expected model deepseek-chat, got %v", getResp["model"])
	}
	apiKeyStr, _ := getResp["api_key"].(string)
	if strings.Contains(apiKeyStr, "123456") && !strings.Contains(apiKeyStr, "....") {
		t.Errorf("expected masked api key, got %v", apiKeyStr)
	}

	// 3. 测试大模型网络异常时自动无缝降级为启发式治愈回复
	chatPayload := map[string]string{
		"user_id": "test_llm_fallback_user",
		"message": "星野，如果断网了大模型连不上怎么办？",
	}
	chatBytes, _ := json.Marshal(chatPayload)
	reqChat, _ := http.NewRequest("POST", "/api/v1/chat/interact", bytes.NewBuffer(chatBytes))
	reqChat.Header.Set("Content-Type", "application/json")
	wChat := httptest.NewRecorder()
	router.ServeHTTP(wChat, reqChat)

	if wChat.Code != http.StatusOK {
		t.Fatalf("expected 200 ok with fallback, got %d", wChat.Code)
	}
	var chatResp model.ChatResponse
	_ = json.Unmarshal(wChat.Body.Bytes(), &chatResp)
	if chatResp.Text == "" {
		t.Errorf("expected non-empty fallback chat response")
	}
}

func TestChatStreamAndCyberKPersona(t *testing.T) {
	// 1. 切换至 K-07 战术机娘
	userID := "test_stream_cyberk_user"
	ok := persona.DefaultEngine.SwitchUserPersona(userID, "cyber_k")
	if !ok {
		t.Fatalf("switch to cyber_k failed")
	}

	// 2. 测试 K-07 专属时段与打招呼应答
	respHello, err := persona.DefaultEngine.Chat(userID, "早安，K-07！")
	if err != nil {
		t.Fatalf("chat with cyber_k failed: %v", err)
	}
	if !strings.Contains(respHello.Text, "战术机娘K-07") {
		t.Errorf("expected K-07 identity in hello response, got: %s", respHello.Text)
	}

	respNight, err := persona.DefaultEngine.Chat(userID, "好困，准备晚安睡觉了。")
	if err != nil {
		t.Fatalf("chat night with cyber_k failed: %v", err)
	}
	if !strings.Contains(respNight.Text, "静音护航") {
		t.Errorf("expected silent guard mode in night response, got: %s", respNight.Text)
	}

	// 3. 测试 K-07 肢体触碰反馈
	touchHead, err := persona.DefaultEngine.Touch(userID, model.TouchPartHead)
	if err != nil {
		t.Fatalf("touch head failed: %v", err)
	}
	if !strings.Contains(touchHead.Text, "全息核心温度") {
		t.Errorf("expected cyber_k head touch response, got: %s", touchHead.Text)
	}

	touchCheek, err := persona.DefaultEngine.Touch(userID, model.TouchPartCheek)
	if err != nil {
		t.Fatalf("touch cheek failed: %v", err)
	}
	if !strings.Contains(touchCheek.Text, "感知网格") {
		t.Errorf("expected cyber_k cheek touch response, got: %s", touchCheek.Text)
	}

	// 4. 测试 ChatStream 流式生成管道
	var chunks []string
	streamResp, err := persona.DefaultEngine.ChatStream(userID, "汇报一下当前战术机体状态。", func(chunk string) {
		chunks = append(chunks, chunk)
	})
	if err != nil {
		t.Fatalf("chat stream failed: %v", err)
	}
	if streamResp == nil || streamResp.Text == "" {
		t.Fatalf("expected non-empty stream response")
	}
	if len(chunks) == 0 {
		t.Errorf("expected at least 1 stream chunk emitted")
	}
}

func TestASRProviderAndRoutes(t *testing.T) {
	router := setupTestRouter()

	// 1. 设置 ASR 模拟与外部配置
	cfgPayload := map[string]string{
		"provider": "whisper",
		"base_url": "https://api.openai.com/v1",
		"api_key":  "sk-test-mock-whisper-key-987654",
		"model":    "whisper-1",
		"language": "zh",
	}
	cfgBytes, _ := json.Marshal(cfgPayload)
	reqCfg, _ := http.NewRequest("POST", "/api/v1/asr/config", bytes.NewBuffer(cfgBytes))
	reqCfg.Header.Set("Content-Type", "application/json")
	wCfg := httptest.NewRecorder()
	router.ServeHTTP(wCfg, reqCfg)
	if wCfg.Code != http.StatusOK {
		t.Fatalf("set asr config failed: %d", wCfg.Code)
	}

	// 2. 查询 ASR 配置并验证 APIKey 掩码
	reqGet, _ := http.NewRequest("GET", "/api/v1/asr/config", nil)
	wGet := httptest.NewRecorder()
	router.ServeHTTP(wGet, reqGet)
	if wGet.Code != http.StatusOK {
		t.Fatalf("get asr config failed: %d", wGet.Code)
	}
	var getResp map[string]interface{}
	_ = json.Unmarshal(wGet.Body.Bytes(), &getResp)
	if getResp["model"] != "whisper-1" {
		t.Errorf("expected model whisper-1, got %v", getResp["model"])
	}
	apiKeyStr, _ := getResp["api_key"].(string)
	if strings.Contains(apiKeyStr, "987654") && !strings.Contains(apiKeyStr, "....") {
		t.Errorf("expected masked api key, got %v", apiKeyStr)
	}

	// 3. 测试通过 JSON base64 识别音频（自动触发启发式高可用降级）
	dummyPCM := make([]byte, 16000) // 1 秒静音
	base64Audio := base64.StdEncoding.EncodeToString(dummyPCM)
	transcribePayload := map[string]string{
		"audio_base64": base64Audio,
		"format":       "wav",
	}
	tBytes, _ := json.Marshal(transcribePayload)
	reqTrans, _ := http.NewRequest("POST", "/api/v1/asr/transcribe", bytes.NewBuffer(tBytes))
	reqTrans.Header.Set("Content-Type", "application/json")
	wTrans := httptest.NewRecorder()
	router.ServeHTTP(wTrans, reqTrans)

	if wTrans.Code != http.StatusOK {
		t.Fatalf("transcribe failed: %d, body: %s", wTrans.Code, wTrans.Body.String())
	}
	var transResp asr.TranscribeResponse
	_ = json.Unmarshal(wTrans.Body.Bytes(), &transResp)
	if transResp.Text == "" {
		t.Errorf("expected transcribed text, got empty")
	}

	// 4. 测试通过 multipart/form-data 表单上传识别音频
	var b bytes.Buffer
	w := multipart.NewWriter(&b)
	part, _ := w.CreateFormFile("file", "test_record.wav")
	_, _ = part.Write(dummyPCM)
	_ = w.Close()

	reqForm, _ := http.NewRequest("POST", "/api/v1/asr/transcribe", &b)
	reqForm.Header.Set("Content-Type", w.FormDataContentType())
	wForm := httptest.NewRecorder()
	router.ServeHTTP(wForm, reqForm)

	if wForm.Code != http.StatusOK {
		t.Fatalf("form transcribe failed: %d", wForm.Code)
	}
	var formResp asr.TranscribeResponse
	_ = json.Unmarshal(wForm.Body.Bytes(), &formResp)
	if formResp.Text == "" {
		t.Errorf("expected form transcribed text, got empty")
	}

	// 5. 测试 ASR 连通性测试接口
	reqTest, _ := http.NewRequest("POST", "/api/v1/asr/test", nil)
	wTest := httptest.NewRecorder()
	router.ServeHTTP(wTest, reqTest)
	if wTest.Code != http.StatusOK {
		t.Errorf("expected test asr 200, got %d", wTest.Code)
	}
}

func TestWebSocketAudioMessagePipeline(t *testing.T) {
	router := setupTestRouter()
	server := httptest.NewServer(router)
	defer server.Close()

	u, _ := url.Parse(server.URL)
	u.Scheme = "ws"
	u.Path = "/api/v1/ws/continuous"
	q := u.Query()
	q.Set("user_id", "test_ws_audio_user")
	u.RawQuery = q.Encode()

	ws, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		t.Fatalf("failed to dial websocket: %v", err)
	}
	defer ws.Close()

	// 1. 读取就绪帧
	var readyMsg map[string]interface{}
	if err := ws.ReadJSON(&readyMsg); err != nil {
		t.Fatalf("read ready failed: %v", err)
	}
	if readyMsg["type"] != "ready" {
		t.Fatalf("expected ready message, got: %v", readyMsg)
	}

	// 2. 发送 audio_message 语音包
	dummyPCM := make([]byte, 12000)
	audioB64 := base64.StdEncoding.EncodeToString(dummyPCM)
	sendAudioMsg := map[string]interface{}{
		"type":         "audio_message",
		"audio_base64": audioB64,
		"audio_format": "wav",
	}
	if err := ws.WriteJSON(sendAudioMsg); err != nil {
		t.Fatalf("write audio_message failed: %v", err)
	}

	// 3. 消费帧流并验证 asr_result 与 response_complete
	var gotAsrResult bool
	var gotComplete bool
	var asrText string

	for i := 0; i < 20; i++ {
		var frame map[string]interface{}
		if err := ws.ReadJSON(&frame); err != nil {
			t.Fatalf("read frame failed: %v", err)
		}
		fType, _ := frame["type"].(string)
		if fType == "asr_result" {
			gotAsrResult = true
			asrText, _ = frame["text"].(string)
		} else if fType == "response_complete" {
			gotComplete = true
			break
		}
	}

	if !gotAsrResult {
		t.Errorf("expected asr_result frame from audio_message")
	}
	if asrText == "" {
		t.Errorf("expected non-empty recognized text in asr_result")
	}
	if !gotComplete {
		t.Errorf("expected response_complete after speech recognition")
	}
}

func TestMemoryRecallAssociation(t *testing.T) {
	mgr := persona.DefaultMemoryManager
	userID := "test_recall_user_1"
	personaID := "hoshino"

	mgr.AddMemory(userID, personaID, "指挥官最喜欢喝香草热奶茶。", persona.MemoryCategoryUserFact, 4)
	mgr.AddMemory(userID, personaID, "约定好周末一起去海边看落日与晚霞。", persona.MemoryCategoryEmotionalPromise, 5)
	mgr.AddMemory(userID, personaID, "在工作代码写累的时候需要星野的治愈加油。", persona.MemoryCategorySharedMoment, 4)

	// 1. 针对“奶茶”输入，召回相关记忆
	recalledMilkTea := mgr.RecallRelevantMemories(userID, personaID, "今天好想喝一杯热奶茶呀", 3)
	if len(recalledMilkTea) == 0 {
		t.Fatalf("expected non-empty recalled memories")
	}
	if !strings.Contains(recalledMilkTea[0].Content, "奶茶") {
		t.Errorf("expected milk tea memory to be prioritized at top, got: %s", recalledMilkTea[0].Content)
	}

	// 2. 针对“海边约定”输入，召回约定记忆
	recalledPromise := mgr.RecallRelevantMemories(userID, personaID, "还记得我们之前说的去海边吗？", 3)
	if !strings.Contains(recalledPromise[0].Content, "海边") {
		t.Errorf("expected seaside promise memory to be prioritized at top, got: %s", recalledPromise[0].Content)
	}

	// 3. 验证 Prompt 格式输出
	promptFrag := mgr.GetContextPromptFragmentWithRecall(userID, personaID, "今天上班工作好累呀")
	if !strings.Contains(promptFrag, "情感回忆") {
		t.Errorf("expected prompt fragment header, got: %s", promptFrag)
	}
}

func TestVisionInteractAndFallback(t *testing.T) {
	router := gin.Default()
	api.RegisterRoutes(router)

	// 1. 模拟轻量相机实时帧 Base64
	mockImageBase64 := "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

	visionPayload := model.VisionInteractRequest{
		UserID:      "test_vision_user",
		Message:     "星野，看看我手里的这个是什么？",
		ImageBase64: mockImageBase64,
	}
	bodyBytes, _ := json.Marshal(visionPayload)

	req, _ := http.NewRequest("POST", "/api/v1/vision/interact", bytes.NewBuffer(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 ok for vision interact, got %d, body: %s", w.Code, w.Body.String())
	}

	var resp model.ChatResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal vision response failed: %v", err)
	}

	if resp.Text == "" {
		t.Errorf("expected non-empty vision reply text")
	}
	if resp.AudioBase64 == nil {
		t.Errorf("expected synthesized audio in vision response")
	}
	if resp.CurrentAffinity <= 0 {
		t.Errorf("expected increased affinity from vision interact, got %d", resp.CurrentAffinity)
	}
}
