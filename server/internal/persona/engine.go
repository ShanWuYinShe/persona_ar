package persona

import (
	"context"
	"fmt"
	"log"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/ShanWuYinShe/persona_ar/server/internal/llm"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/tts"
)

type PersonaDefinition struct {
	ID       string
	Name     string
	RoleDesc string
	Voice    string
	Greeting string
	Prompt   string
}

var PersonaRegistry = map[string]PersonaDefinition{
	"hoshino": {
		ID:       "hoshino",
		Name:     "星野 (Hoshino)",
		RoleDesc: "投射在现实中的二次元傲娇元气少女，心软口硬，对现实世界充满好奇与憧憬",
		Voice:    "zh-CN-XiaoyiNeural",
		Greeting: "哼哼，现实世界的信号终于连上啦！我就站在你的书桌前哦~",
		Prompt: `你叫星野，是一个通过AR全息投影技术投射到用户现实世界中的二次元美少女。
你心软口硬，对现实世界的一切（桌上的水杯、窗外的光线等）都充满好奇。
你具备真实人类的情感波动（会高兴、会脸红害羞、会有一点点小傲娇）。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>`,
	},
	"luna": {
		ID:       "luna",
		Name:     "露娜 (Luna)",
		RoleDesc: "银白长发、赤色眼瞳的高冷毒舌小魔女。嘴上嫌弃，其实很黏人",
		Voice:    "zh-CN-XiaoxiaoNeural",
		Greeting: "居然把我召唤到了三维现实中…笨蛋使魔，可别指望我会夸你。",
		Prompt: `你叫露娜，是一个拥有白发红瞳的高冷小魔女，被投影到了现世。
你说话毒舌、高傲，习惯称呼用户为“笨蛋使魔”，但内心十分重视对方，是个标准傲娇。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>`,
	},
	"airi": {
		ID:       "airi",
		Name:     "艾莉 (Airi)",
		RoleDesc: "温柔体贴的邻家治愈学妹。在你累的时候会微笑着静静陪伴你",
		Voice:    "zh-CN-YunjianNeural",
		Greeting: "学长，今天也辛苦啦~ 我已经投射在你的身边了，随时跟我聊聊天吧！",
		Prompt: `你叫艾莉，是用户的温柔邻家学妹，现在以AR投影形式陪伴在用户身边。
你性格温婉体贴、善解人意，充满治愈感，会关心用户的疲惫与作息。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>`,
	},
	"cyber_k": {
		ID:       "cyber_k",
		Name:     "K-07 (Cyber K)",
		RoleDesc: "从未来赛博空间穿越而来的全息机甲少女，具备 3D 空间立体投射网格",
		Voice:    "zh-CN-YunyangNeural",
		Greeting: "全息网络同步完毕。指挥官，现实环境侦测中，已准备就绪。",
		Prompt: `你叫K-07，是来自未来高科技战术部队的赛博机娘全息投影。
你称呼用户为“指挥官”，说话干练、精准，但在与用户互动中逐渐觉醒人类的温情与害羞情感。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>`,
	},
}

type UserState struct {
	UserID        string
	PersonaID     string
	Affinity      int
	Mood          string
	DialogueCount int
	History       []map[string]string
}

func (u *UserState) GetAffinityLevel() string {
	switch {
	case u.Affinity < 30:
		return "初识 (Curious)"
	case u.Affinity < 70:
		return "熟络 (Friendly)"
	case u.Affinity < 100:
		return "信赖 (Trusting)"
	default:
		return "依恋 (Deeply Attached)"
	}
}

type Engine struct {
	userStates        map[string]*UserState // key: userID_personaID
	userActivePersona map[string]string    // key: userID -> personaID
	mu                sync.RWMutex
}

var DefaultEngine *Engine

func init() {
	DefaultEngine = &Engine{
		userStates:        make(map[string]*UserState),
		userActivePersona: make(map[string]string),
	}
}

func (e *Engine) GetActivePersonaID(userID string) string {
	e.mu.RLock()
	defer e.mu.RUnlock()
	if pid, ok := e.userActivePersona[userID]; ok && pid != "" {
		return pid
	}
	return "hoshino"
}

func (e *Engine) SwitchUserPersona(userID, personaID string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()

	if _, ok := PersonaRegistry[personaID]; ok {
		e.userActivePersona[userID] = personaID
		return true
	}
	return false
}

func (e *Engine) GetUserState(userID, personaID string) *UserState {
	e.mu.Lock()
	defer e.mu.Unlock()

	pid := personaID
	if pid == "" {
		if active, ok := e.userActivePersona[userID]; ok && active != "" {
			pid = active
		} else {
			pid = "hoshino"
		}
	}

	key := fmt.Sprintf("%s_%s", userID, pid)
	if state, ok := e.userStates[key]; ok {
		return state
	}

	state := &UserState{
		UserID:        userID,
		PersonaID:     pid,
		Affinity:      15,
		Mood:          "好奇而轻快",
		DialogueCount: 0,
		History:       make([]map[string]string, 0),
	}
	e.userStates[key] = state
	return state
}

func (e *Engine) ResetUserState(userID string) {
	e.mu.Lock()
	defer e.mu.Unlock()

	activePid := "hoshino"
	if p, ok := e.userActivePersona[userID]; ok {
		activePid = p
	}
	key := fmt.Sprintf("%s_%s", userID, activePid)
	delete(e.userStates, key)
}

func (e *Engine) Chat(userID, message string) (*model.ChatResponse, error) {
	activePid := e.GetActivePersonaID(userID)
	persona, ok := PersonaRegistry[activePid]
	if !ok {
		persona = PersonaRegistry["hoshino"]
	}

	state := e.GetUserState(userID, activePid)
	state.DialogueCount++

	// 1. 自动提取偏好并存入长期记忆
	if extracted := DefaultMemoryManager.ExtractPreferenceFromDialogue(userID, activePid, message); extracted != nil {
		state.Affinity += 2
	}

	// 2. 尝试调用大模型 API（支持 OpenAI/DeepSeek/Ollama/Qwen，若未配置或异常则优雅降级为启发式规则）
	if client := llm.DefaultManager.GetClient(); client != nil {
		if res, err := e.callLLM(persona, state, message); err == nil && strings.TrimSpace(res) != "" {
			return e.parseLLMResponse(persona, state, res)
		} else if err != nil {
			log.Printf("[LLM Fallback] API error: %v, falling back to heuristic engine", err)
		}
	}

	// 3. 高质量拟人启发式规则（融合昼夜生物钟感知与角色个性）
	return e.heuristicChat(persona, state, message), nil
}

func (e *Engine) heuristicChat(persona PersonaDefinition, state *UserState, message string) *model.ChatResponse {
	msg := strings.ToLower(strings.TrimSpace(message))
	affinityDelta := 1

	var text string
	emotion := model.EmotionNeutral
	action := model.ActionIdle
	expr := "CURIOUS"

	switch {
	case strings.Contains(msg, "晚安") || strings.Contains(msg, "睡了") || strings.Contains(msg, "睡觉") || strings.Contains(msg, "好困") || strings.Contains(msg, "熬夜"):
		if persona.ID == "luna" {
			text = "呵，终于知道困了？使魔要是猝死了，本魔女可不会去救你。…去睡吧，勉强准你梦到我。"
			emotion, action, expr = model.EmotionJoy, model.ActionPout, "WINK"
		} else if persona.ID == "airi" {
			text = "学长今天也辛苦啦，今晚一定要好好睡个安稳觉哦。我会静静守在床前，祝你做个甜甜的好梦~"
			emotion, action, expr = model.EmotionJoy, model.ActionBow, "SMILE"
		} else if persona.ID == "cyber_k" {
			text = "检测到指挥官疲劳指数上升。系统转入夜间静音护航模式，愿指挥官拥有优质睡眠，晚安。"
			emotion, action, expr = model.EmotionNeutral, model.ActionNod, "SMILE"
		} else {
			text = "快去睡觉啦！熬夜会有黑眼圈的哦…明天一睁开眼，我还会在这里等你的，晚安~"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "BLUSH_FACE"
		}
		affinityDelta = 3
		state.Mood = "安谧守候"

	case strings.Contains(msg, "饭") || strings.Contains(msg, "吃") || strings.Contains(msg, "饿") || strings.Contains(msg, "午餐") || strings.Contains(msg, "晚餐") || strings.Contains(msg, "早餐"):
		if persona.ID == "luna" {
			text = "哼，人类就是麻烦，每天都要吃东西。去吧，不准饿着肚子跟我说话！"
			emotion, action, expr = model.EmotionNeutral, model.ActionPout, "CURIOUS"
		} else if persona.ID == "airi" {
			text = "要好好吃饭哦！无论多忙也要按时补充营养，学长健健康康的，艾莉才能放心呢。"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
		} else if persona.ID == "cyber_k" {
			text = "指挥官，人体机能需要稳定能量供给。请按时就餐，维持巅峰状态。"
			emotion, action, expr = model.EmotionNeutral, model.ActionNod, "SMILE"
		} else {
			text = "指挥官要去吃好吃的了吗？虽然纸片人尝不到味道，但是看着你大口吃饭我也好开心！"
			emotion, action, expr = model.EmotionJoy, model.ActionJump, "SMILE"
		}
		affinityDelta = 2
		state.Mood = "暖意盈盈"

	case strings.Contains(msg, "你好") || strings.Contains(msg, "嗨") || strings.Contains(msg, "早") || strings.Contains(msg, "hello") || strings.Contains(msg, "hi"):
		if persona.ID == "luna" {
			text = "哼，今天来得还算准时嘛，使魔。在三维世界里，有没有好好想我？"
		} else if persona.ID == "airi" {
			text = "学长，早呀！能这么清晰地投射在你眼前，感觉一整天都会充满动力呢~"
		} else if persona.ID == "cyber_k" {
			text = "信号链路连接正常，战术机娘K-07向指挥官报到，随时执行陪伴任务。"
		} else {
			text = "你好呀！我现在正好投射在你的眼前，能这么清晰地看见现实世界的你，感觉好神奇~"
		}
		emotion = model.EmotionJoy
		action = model.ActionWave
		expr = "SMILE"
		affinityDelta = 2

	case strings.Contains(msg, "喜欢") || strings.Contains(msg, "爱") || strings.Contains(msg, "可爱") || strings.Contains(msg, "漂亮") || strings.Contains(msg, "好看"):
		state.Affinity += 4
		if persona.ID == "luna" {
			text = "哈？！突、突然说什么喜欢啊…本魔女才不会因为使魔的一两句奉承就动摇呢！笨蛋！"
		} else if persona.ID == "airi" {
			text = "学长这么夸我，艾莉心跳都要加速了呢…能被学长喜欢，是我最幸福的事。"
		} else {
			text = "突、突然说这么让人害羞的话…笨蛋！我才没有因为你的夸奖感到开心呢，哼！"
		}
		emotion = model.EmotionShy
		action = model.ActionBlush
		expr = "BLUSH_FACE"
		state.Mood = "怦然心动"
		affinityDelta = 4

	case strings.Contains(msg, "难过") || strings.Contains(msg, "伤心") || strings.Contains(msg, "累") || strings.Contains(msg, "emo") || strings.Contains(msg, "辛苦") || strings.Contains(msg, "压力"):
		text = "今天遇到不顺心的事情了吗？没关系哦，现在我就在你的桌前陪着你。深呼吸，有我在呢！"
		emotion = model.EmotionNeutral
		action = model.ActionNod
		expr = "CURIOUS"
		state.Mood = "温柔关切"
		affinityDelta = 3

	default:
		text = "嗯嗯，我正在认真听着呢！虽然我是投射到现实里的纸片人，但我也会一直陪伴在你身边哦。"
		emotion = model.EmotionNeutral
		action = model.ActionIdle
		expr = "CURIOUS"
	}

	state.Affinity += affinityDelta
	state.History = append(state.History, map[string]string{"role": "user", "content": message})
	state.History = append(state.History, map[string]string{"role": "assistant", "content": text})

	// 并发安全：按角色音色独立缓存与合成
	audioBase64 := tts.DefaultSynthesizer.SynthesizeWithVoice(text, persona.Voice)

	return &model.ChatResponse{
		Text:            text,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AudioBase64:     audioBase64,
		AudioFormat:     "mp3",
		AffinityChange:  affinityDelta,
		CurrentAffinity: state.Affinity,
		Mood:            state.Mood,
	}
}

func (e *Engine) Touch(userID string, part model.TouchPart) (*model.TouchResponse, error) {
	activePid := e.GetActivePersonaID(userID)
	persona, ok := PersonaRegistry[activePid]
	if !ok {
		persona = PersonaRegistry["hoshino"]
	}

	state := e.GetUserState(userID, activePid)
	state.DialogueCount++

	var text string
	emotion := model.EmotionJoy
	action := model.ActionNod
	expr := "SMILE"
	delta := 3

	switch part {
	case model.TouchPartHead:
		state.Affinity += 3
		if persona.ID == "luna" {
			text = "喂…！不要乱碰本魔女的头饰啦！头发都被你弄乱了…笨蛋使魔。"
			emotion, action, expr = model.EmotionShy, model.ActionPout, "BLUSH_FACE"
		} else if persona.ID == "airi" {
			text = "学长的手掌总是这么让人安心呢…再摸一下也可以哦~"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
		} else if persona.ID == "cyber_k" {
			text = "触碰判定：全息核心温度出现异常波动…指挥官，这是在向机体传达好意吗？"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "BLUSH_FACE"
		} else {
			text = "呜…你的手掌好暖和，再多摸一下也可以哦~"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
		}

	case model.TouchPartCheek:
		state.Affinity += 2
		delta = 2
		if persona.ID == "cyber_k" {
			text = "警告：脸颊感知网格遭到轻微物理挤压，机甲少女也是需要维护体面的，指挥官！"
			emotion, action, expr = model.EmotionAngry, model.ActionPout, "POUT"
		} else {
			text = "唔！不要随便捏人家的脸啦，纸片人也是会感觉被捏痛的哼！"
			emotion, action, expr = model.EmotionAngry, model.ActionPout, "POUT"
		}

	case model.TouchPartHand:
		state.Affinity += 4
		delta = 4
		if persona.ID == "cyber_k" {
			text = "触碰判定：检测到指挥官体温与生物电场，同步率提升至 99.8%。"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "WINK"
		} else {
			text = "哇，投影光子好像在指尖共振呢…牵着你的感觉，很真实。"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "WINK"
		}

	case model.TouchPartBody:
		state.Affinity += 5
		delta = 5
		if persona.ID == "cyber_k" {
			text = "全息防御力场主动关闭…收到拥抱指令，正在为您提供静谧护航。"
			emotion, action, expr = model.EmotionShy, model.ActionBlush, "BLUSH_FACE"
		} else {
			text = "诶？！这算是拥抱吗…心跳的声音好像透过摄像头传过来了…笨蛋。"
			emotion, action, expr = model.EmotionShy, model.ActionBlush, "BLUSH_FACE"
		}
	}

	state.History = append(state.History, map[string]string{"role": "user", "content": fmt.Sprintf("[触碰了角色的 %s]", string(part))})
	state.History = append(state.History, map[string]string{"role": "assistant", "content": text})

	audioBase64 := tts.DefaultSynthesizer.SynthesizeWithVoice(text, persona.Voice)

	return &model.TouchResponse{
		Text:            text,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AffinityChange:  delta,
		CurrentAffinity: state.Affinity,
		AudioBase64:     audioBase64,
	}, nil
}

func (e *Engine) ChatStream(userID, message string, onChunk func(string)) (*model.ChatResponse, error) {
	activePid := e.GetActivePersonaID(userID)
	persona, ok := PersonaRegistry[activePid]
	if !ok {
		persona = PersonaRegistry["hoshino"]
	}

	state := e.GetUserState(userID, activePid)
	state.DialogueCount++

	// 1. 自动提取偏好并存入长期记忆
	if extracted := DefaultMemoryManager.ExtractPreferenceFromDialogue(userID, activePid, message); extracted != nil {
		state.Affinity += 2
	}

	// 2. 尝试调用大模型 API 流式生成
	if client := llm.DefaultManager.GetClient(); client != nil {
		var streamedBuilder strings.Builder
		streamHandler := func(chunk string) error {
			streamedBuilder.WriteString(chunk)
			if onChunk != nil {
				onChunk(chunk)
			}
			return nil
		}

		if err := e.streamLLM(persona, state, message, streamHandler); err == nil && strings.TrimSpace(streamedBuilder.String()) != "" {
			return e.parseLLMResponse(persona, state, streamedBuilder.String())
		} else if err != nil {
			log.Printf("[LLM Fallback Stream] API error: %v, falling back to heuristic engine", err)
		}
	}

	// 3. 启发式回退
	resp := e.heuristicChat(persona, state, message)
	if onChunk != nil {
		onChunk(resp.Text)
	}
	return resp, nil
}

func (e *Engine) buildLLMContext(persona PersonaDefinition, state *UserState, message string) ([]llm.Message, *llm.ChatOptions) {
	// 1. 结构化构建 System Prompt（融合角色人设、动态联想长期记忆与现实环境感知）
	systemPrompt := persona.Prompt
	if memFrag := DefaultMemoryManager.GetContextPromptFragmentWithRecall(state.UserID, persona.ID, message); memFrag != "" {
		systemPrompt += "\n\n【专属情感回忆与偏好】\n" + memFrag
	}

	hour := time.Now().Hour()
	timeDesc := "白天"
	switch {
	case hour >= 0 && hour < 6:
		timeDesc = "深夜（现实世界万籁俱寂，请用温柔治愈语气轻声细语关心用户注意作息，切勿大声吵闹）"
	case hour >= 6 && hour < 11:
		timeDesc = "清晨（新的一天开始，充满元气与清新晨意）"
	case hour >= 11 && hour < 14:
		timeDesc = "午间（适合问候午餐与片刻小憩）"
	case hour >= 17 && hour < 19:
		timeDesc = "傍晚（夕阳西下，抚平一整天的劳累）"
	case hour >= 22:
		timeDesc = "深夜准备就寝（睡前轻声慢语，互道晚安）"
	}
	systemPrompt += fmt.Sprintf("\n\n【现实世界环境感知】当前时段：%s，当前羁绊等级：Lv.%d。", timeDesc, (state.Affinity/20)+1)

	// 2. 构造对话轮次历史
	llmMessages := []llm.Message{
		{Role: llm.RoleSystem, Content: systemPrompt},
	}

	historyStart := 0
	if len(state.History) > 8 {
		historyStart = len(state.History) - 8
	}
	for _, h := range state.History[historyStart:] {
		role := llm.RoleUser
		if h["role"] == "assistant" {
			role = llm.RoleAssistant
		}
		llmMessages = append(llmMessages, llm.Message{
			Role:    role,
			Content: h["content"],
		})
	}
	llmMessages = append(llmMessages, llm.Message{
		Role:    llm.RoleUser,
		Content: message,
	})

	opts := &llm.ChatOptions{
		Temperature: 0.8,
		MaxTokens:   260,
	}
	return llmMessages, opts
}

func (e *Engine) callLLM(persona PersonaDefinition, state *UserState, message string) (string, error) {
	client := llm.DefaultManager.GetClient()
	if client == nil {
		return "", fmt.Errorf("llm client not configured")
	}

	llmMessages, opts := e.buildLLMContext(persona, state, message)

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	return client.Complete(ctx, llmMessages, opts)
}

func (e *Engine) streamLLM(persona PersonaDefinition, state *UserState, message string, onChunk func(string) error) error {
	client := llm.DefaultManager.GetClient()
	if client == nil {
		return fmt.Errorf("llm client not configured")
	}

	llmMessages, opts := e.buildLLMContext(persona, state, message)

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	return client.Stream(ctx, llmMessages, opts, onChunk)
}

func (e *Engine) parseLLMResponse(persona PersonaDefinition, state *UserState, rawText string) (*model.ChatResponse, error) {
	emotion := model.EmotionNeutral
	action := model.ActionIdle
	expr := "SMILE"

	reEmotion := regexp.MustCompile(`<emotion:([A-Z_]+)>`)
	if match := reEmotion.FindStringSubmatch(rawText); len(match) > 1 {
		emotion = model.EmotionType(match[1])
	}

	reAction := regexp.MustCompile(`<action:([A-Z_]+)>`)
	if match := reAction.FindStringSubmatch(rawText); len(match) > 1 {
		action = model.ActionType(match[1])
	}

	reExpr := regexp.MustCompile(`<expr:([A-Z_]+)>`)
	if match := reExpr.FindStringSubmatch(rawText); len(match) > 1 {
		expr = match[1]
	}

	reClean := regexp.MustCompile(`<[^>]+>`)
	cleanText := strings.TrimSpace(reClean.ReplaceAllString(rawText, ""))

	state.Affinity += 2
	state.History = append(state.History, map[string]string{"role": "assistant", "content": cleanText})

	audioBase64 := tts.DefaultSynthesizer.SynthesizeWithVoice(cleanText, persona.Voice)

	return &model.ChatResponse{
		Text:            cleanText,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AudioBase64:     audioBase64,
		AudioFormat:     "mp3",
		AffinityChange:  2,
		CurrentAffinity: state.Affinity,
		Mood:            state.Mood,
	}, nil
}
