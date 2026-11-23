package test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/ShanWuYinShe/persona_ar/server/internal/llm"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
)

// TestHeuristicActionVariety 验证内置启发式大脑可以把「点头/摇头/鞠躬/得意/打哈欠/害羞/挥手/跳跃」
// 等全部 3D 手办动作真正用出来（不依赖外部大模型 key），保证 3D 动作系统在离线开箱场景也可达。
func TestHeuristicActionVariety(t *testing.T) {
	r := setupTestRouter()

	// 强制走启发式规则：配置一个必定连接失败的外部大模型客户端（等价于用户未配置有效 key 的真实离线场景）
	llm.DefaultManager.Configure(llm.ProviderConfig{
		Provider: "openai",
		BaseURL:  "http://127.0.0.1:1",
		APIKey:   "test-invalid-key",
		Model:    "test-model",
	})
	defer llm.DefaultManager.Configure(llm.ProviderConfig{Provider: "mock"})

	cases := []struct {
		name   string
		msg    string
		action model.ActionType
	}{
		{"挥手问候", "你好呀星野", model.ActionWave},
		{"吃饭开心跳", "我要去吃饭啦", model.ActionJump},
		{"害羞夸奖", "你今天好可爱", model.ActionBlush},
		{"鞠躬道歉", "对不起，是我的错", model.ActionBow},
		{"摇头拒绝", "不要嘛，不行", model.ActionShakeHead},
		{"得意被夸", "你真棒真厉害", model.ActionSmug},
		{"打哈欠困", "我打哈欠了，有点犯困", model.ActionYawn},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			payload := map[string]string{
				"user_id": "test_action_variety_user",
				"message": tc.msg,
			}
			body, _ := json.Marshal(payload)
			req, _ := http.NewRequest("POST", "/api/v1/chat/interact", bytes.NewBuffer(body))
			req.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			r.ServeHTTP(w, req)
			if w.Code != http.StatusOK {
				t.Fatalf("chat failed: %d %s", w.Code, w.Body.String())
			}
			var resp model.ChatResponse
			if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
				t.Fatalf("unmarshal failed: %v", err)
			}
			if resp.Action != tc.action {
				t.Errorf("message %q expected action %s, got %s (text=%q)", tc.msg, tc.action, resp.Action, resp.Text)
			}
		})
	}
}