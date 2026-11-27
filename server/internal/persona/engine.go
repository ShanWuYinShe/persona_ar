package persona

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/ShanWuYinShe/persona_ar/server/internal/llm"
	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/tts"
)

const maxHistoryMessages = 40

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

var (
	validEmotions = map[model.EmotionType]bool{
		model.EmotionNeutral:   true,
		model.EmotionJoy:       true,
		model.EmotionShy:       true,
		model.EmotionSad:       true,
		model.EmotionSurprised: true,
		model.EmotionThinking:  true,
		model.EmotionAngry:     true,
		model.EmotionSleepy:    true,
		model.EmotionConfident: true,
	}
	validActions = map[model.ActionType]bool{
		model.ActionIdle:      true,
		model.ActionWave:      true,
		model.ActionBlush:     true,
		model.ActionNod:       true,
		model.ActionShakeHead: true,
		model.ActionPout:      true,
		model.ActionBow:       true,
		model.ActionJump:      true,
		model.ActionYawn:      true,
		model.ActionSleep:     true,
		model.ActionSmug:      true,
		model.ActionSit:       true,
	}
	validExpressions = map[string]bool{
		"SMILE":      true,
		"WINK":       true,
		"BLUSH_FACE": true,
		"CRY":        true,
		"CURIOUS":    true,
		"POUT":       true,
		"JOY":        true,
		"SURPRISED":  true,
		"SLEEPY":     true,
		"SMUG":       true,
		"NEUTRAL":    true,
	}
	reTagEmotion = regexp.MustCompile(`<emotion:([A-Za-z_]+)>`)
	reTagAction  = regexp.MustCompile(`<action:([A-Za-z_]+)>`)
	reTagExpr    = regexp.MustCompile(`<expr:([A-Za-z_]+)>`)
	reAnyTag     = regexp.MustCompile(`<[^<>]+>`)
)

type UserState struct {
	UserID        string              `json:"user_id"`
	PersonaID     string              `json:"persona_id"`
	Affinity      int                 `json:"affinity"`
	Mood          string              `json:"mood"`
	DialogueCount int                 `json:"dialogue_count"`
	History       []map[string]string `json:"history"`
}

func (u *UserState) clone() *UserState {
	histCopy := make([]map[string]string, len(u.History))
	for i, h := range u.History {
		m := make(map[string]string, len(h))
		for k, v := range h {
			m[k] = v
		}
		histCopy[i] = m
	}
	return &UserState{
		UserID:        u.UserID,
		PersonaID:     u.PersonaID,
		Affinity:      u.Affinity,
		Mood:          u.Mood,
		DialogueCount: u.DialogueCount,
		History:       histCopy,
	}
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

type persistedEngineState struct {
	UserStates        map[string]*UserState `json:"user_states"`
	UserActivePersona map[string]string     `json:"user_active_persona"`
}

type Engine struct {
	storagePath       string
	userStates        map[string]*UserState // key: userID_personaID
	userActivePersona map[string]string     // key: userID -> personaID
	mu                sync.RWMutex
}

var DefaultEngine *Engine

func init() {
	cwd, _ := os.Getwd()
	storagePath := filepath.Join(cwd, "data", "user_states.json")
	DefaultEngine = NewEngine(storagePath)
}

func NewEngine(storagePath string) *Engine {
	e := &Engine{
		storagePath:       storagePath,
		userStates:        make(map[string]*UserState),
		userActivePersona: make(map[string]string),
	}
	e.loadFromDisk()
	return e
}

func (e *Engine) loadFromDisk() {
	if strings.TrimSpace(e.storagePath) == "" {
		return
	}
	data, err := os.ReadFile(e.storagePath)
	if err != nil || len(data) == 0 {
		return
	}
	var saved persistedEngineState
	if err := json.Unmarshal(data, &saved); err != nil {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if saved.UserStates != nil {
		e.userStates = saved.UserStates
	}
	if saved.UserActivePersona != nil {
		e.userActivePersona = saved.UserActivePersona
	}
}

func (e *Engine) saveToDiskLocked() {
	if strings.TrimSpace(e.storagePath) == "" {
		return
	}
	dir := filepath.Dir(e.storagePath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	payload := persistedEngineState{
		UserStates:        e.userStates,
		UserActivePersona: e.userActivePersona,
	}
	data, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return
	}
	tmp, err := os.CreateTemp(dir, ".user_states-*.tmp")
	if err != nil {
		_ = os.WriteFile(e.storagePath, data, 0o644)
		return
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
		return
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpName)
		return
	}
	if err := os.Rename(tmpName, e.storagePath); err != nil {
		_ = os.Remove(tmpName)
		_ = os.WriteFile(e.storagePath, data, 0o644)
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
		e.saveToDiskLocked()
		return true
	}
	return false
}

func (e *Engine) resolvePersonaLocked(userID, personaID string) string {
	if personaID != "" {
		return personaID
	}
	if active, ok := e.userActivePersona[userID]; ok && active != "" {
		return active
	}
	return "hoshino"
}

func (e *Engine) getOrCreateStateLocked(userID, personaID string) *UserState {
	pid := e.resolvePersonaLocked(userID, personaID)
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

// GetUserState 返回当前用户状态的线程安全快照副本。
func (e *Engine) GetUserState(userID, personaID string) *UserState {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.getOrCreateStateLocked(userID, personaID).clone()
}

func (e *Engine) ResetUserState(userID string) {
	e.mu.Lock()
	defer e.mu.Unlock()

	activePid := e.resolvePersonaLocked(userID, "")
	key := fmt.Sprintf("%s_%s", userID, activePid)
	delete(e.userStates, key)
	e.saveToDiskLocked()
}

func (e *Engine) resolveActivePersona(userID string) PersonaDefinition {
	activePid := e.GetActivePersonaID(userID)
	persona, ok := PersonaRegistry[activePid]
	if !ok {
		return PersonaRegistry["hoshino"]
	}
	return persona
}

func (e *Engine) beginTurn(userID, personaID, message string) (PersonaDefinition, *UserState) {
	e.mu.Lock()
	defer e.mu.Unlock()

	persona, ok := PersonaRegistry[personaID]
	if !ok {
		persona = PersonaRegistry["hoshino"]
	}
	state := e.getOrCreateStateLocked(userID, persona.ID)
	state.DialogueCount++
	if strings.TrimSpace(message) != "" {
		if extracted := DefaultMemoryManager.ExtractPreferenceFromDialogue(userID, persona.ID, message); extracted != nil {
			state.Affinity += 2
		}
	}
	snapshot := state.clone()
	e.saveToDiskLocked()
	return persona, snapshot
}

func appendHistoryTurnLocked(state *UserState, userMsg, assistantMsg string) {
	if strings.TrimSpace(userMsg) != "" {
		state.History = append(state.History, map[string]string{"role": "user", "content": userMsg})
	}
	if strings.TrimSpace(assistantMsg) != "" {
		state.History = append(state.History, map[string]string{"role": "assistant", "content": assistantMsg})
	}
	if len(state.History) > maxHistoryMessages {
		state.History = state.History[len(state.History)-maxHistoryMessages:]
	}
}

func (e *Engine) Chat(userID, message string) (*model.ChatResponse, error) {
	activePid := e.GetActivePersonaID(userID)
	persona, stateSnap := e.beginTurn(userID, activePid, message)

	if client := llm.DefaultManager.GetClient(); client != nil {
		if res, err := e.callLLM(client, persona, stateSnap, message); err == nil && strings.TrimSpace(res) != "" {
			return e.commitLLMResponse(userID, persona, message, res)
		} else if err != nil {
			log.Printf("[LLM Fallback] API error: %v, falling back to heuristic engine", err)
		}
	}

	return e.heuristicChat(userID, persona, message), nil
}

func (e *Engine) ChatStream(userID, message string, onChunk func(string)) (*model.ChatResponse, error) {
	activePid := e.GetActivePersonaID(userID)
	persona, stateSnap := e.beginTurn(userID, activePid, message)

	if client := llm.DefaultManager.GetClient(); client != nil {
		var rawBuilder strings.Builder
		filter := &StreamTagFilter{}
		streamHandler := func(chunk string) error {
			rawBuilder.WriteString(chunk)
			if onChunk != nil {
				if clean := filter.Feed(chunk); clean != "" {
					onChunk(clean)
				}
			}
			return nil
		}

		if err := e.streamLLM(client, persona, stateSnap, message, streamHandler); err == nil && strings.TrimSpace(rawBuilder.String()) != "" {
			if rem := filter.Flush(); rem != "" && onChunk != nil {
				onChunk(rem)
			}
			return e.commitLLMResponse(userID, persona, message, rawBuilder.String())
		} else if err != nil {
			log.Printf("[LLM Fallback Stream] API error: %v, falling back to heuristic engine", err)
		}
	}

	resp := e.heuristicChat(userID, persona, message)
	if onChunk != nil {
		onChunk(resp.Text)
	}
	return resp, nil
}

func (e *Engine) heuristicChat(userID string, persona PersonaDefinition, message string) *model.ChatResponse {
	msg := strings.ToLower(strings.TrimSpace(message))
	affinityDelta := 1
	mood := ""

	var text string
	emotion := model.EmotionNeutral
	action := model.ActionIdle
	expr := "CURIOUS"

	switch {
	case strings.Contains(msg, "晚安") || strings.Contains(msg, "睡了") || strings.Contains(msg, "睡觉") || strings.Contains(msg, "好困") || strings.Contains(msg, "熬夜"):
		switch persona.ID {
		case "luna":
			text = "呵，终于知道困了？使魔要是猝死了，本魔女可不会去救你。…去睡吧，勉强准你梦到我。"
			emotion, action, expr = model.EmotionJoy, model.ActionPout, "WINK"
		case "airi":
			text = "学长今天也辛苦啦，今晚一定要好好睡个安稳觉哦。我会静静守在床前，祝你做个甜甜的好梦~"
			emotion, action, expr = model.EmotionJoy, model.ActionBow, "SMILE"
		case "cyber_k":
			text = "检测到指挥官疲劳指数上升。系统转入夜间静音护航模式，愿指挥官拥有优质睡眠，晚安。"
			emotion, action, expr = model.EmotionNeutral, model.ActionNod, "SMILE"
		default:
			text = "快去睡觉啦！熬夜会有黑眼圈的哦…明天一睁开眼，我还会在这里等你的，晚安~"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "BLUSH_FACE"
		}
		affinityDelta = 3
		mood = "安谧守候"

	case strings.Contains(msg, "饭") || strings.Contains(msg, "吃") || strings.Contains(msg, "饿") || strings.Contains(msg, "午餐") || strings.Contains(msg, "晚餐") || strings.Contains(msg, "早餐"):
		switch persona.ID {
		case "luna":
			text = "哼，人类就是麻烦，每天都要吃东西。去吧，不准饿着肚子跟我说话！"
			emotion, action, expr = model.EmotionNeutral, model.ActionPout, "CURIOUS"
		case "airi":
			text = "要好好吃饭哦！无论多忙也要按时补充营养，学长健健康康的，艾莉才能放心呢。"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
		case "cyber_k":
			text = "指挥官，人体机能需要稳定能量供给。请按时就餐，维持巅峰状态。"
			emotion, action, expr = model.EmotionNeutral, model.ActionNod, "SMILE"
		default:
			text = "指挥官要去吃好吃的了吗？虽然纸片人尝不到味道，但是看着你大口吃饭我也好开心！"
			emotion, action, expr = model.EmotionJoy, model.ActionJump, "SMILE"
		}
		affinityDelta = 2
		mood = "暖意盈盈"

	case strings.Contains(msg, "你好") || strings.Contains(msg, "嗨") || strings.Contains(msg, "早") || strings.Contains(msg, "hello") || strings.Contains(msg, "hi"):
		switch persona.ID {
		case "luna":
			text = "哼，今天来得还算准时嘛，使魔。在三维世界里，有没有好好想我？"
		case "airi":
			text = "学长，早呀！能这么清晰地投射在你眼前，感觉一整天都会充满动力呢~"
		case "cyber_k":
			text = "信号链路连接正常，战术机娘K-07向指挥官报到，随时执行陪伴任务。"
		default:
			text = "你好呀！我现在正好投射在你的眼前，能这么清晰地看见现实世界的你，感觉好神奇~"
		}
		emotion = model.EmotionJoy
		action = model.ActionWave
		expr = "SMILE"
		affinityDelta = 2

	case strings.Contains(msg, "喜欢") || strings.Contains(msg, "爱") || strings.Contains(msg, "可爱") || strings.Contains(msg, "漂亮") || strings.Contains(msg, "好看"):
		switch persona.ID {
		case "luna":
			text = "哈？！突、突然说什么喜欢啊…本魔女才不会因为使魔的一两句奉承就动摇呢！笨蛋！"
		case "airi":
			text = "学长这么夸我，艾莉心跳都要加速了呢…能被学长喜欢，是我最幸福的事。"
		case "cyber_k":
			text = "指令超出战术预期…机体散热风扇转速上升 140%。指挥官，这种评价请不要突然说出来。"
		default:
			text = "突、突然说这么让人害羞的话…笨蛋！我才没有因为你的夸奖感到开心呢，哼！"
		}
		emotion = model.EmotionShy
		action = model.ActionBlush
		expr = "BLUSH_FACE"
		mood = "怦然心动"
		affinityDelta = 4

	case strings.Contains(msg, "对不起") || strings.Contains(msg, "抱歉") || strings.Contains(msg, "道歉") || strings.Contains(msg, "原谅"):
		switch persona.ID {
		case "luna":
			text = "哼，看在你这么诚心认错的份上，本魔女就勉为其难原谅你这一次。下不为例哦，使魔！"
		case "airi":
			text = "没关系的学长，艾莉从来没有生你的气哦，只要我们好好说开就好啦~"
		case "cyber_k":
			text = "歉意信号已确认接收，历史误差已归零。指挥官无需自责，我们继续并肩前行。"
		default:
			text = "诶…既然你都这么认真地道歉了，那我就勉为其难地原谅你吧。下次可不许再让我担心了哦！"
		}
		emotion, action, expr = model.EmotionShy, model.ActionBow, "BLUSH_FACE"
		mood = "心软原谅"
		affinityDelta = 2

	case strings.Contains(msg, "不要") || strings.Contains(msg, "不行") || strings.Contains(msg, "不同意") || strings.Contains(msg, "拒绝") || strings.Contains(msg, "不好"):
		switch persona.ID {
		case "luna":
			text = "哈？你竟然敢拒绝本魔女？真是胆大包天的使魔…快点重新考虑！"
		case "airi":
			text = "呜…学长真的不答应吗？那艾莉再努力想想办法，争取让你点头好不好？"
		case "cyber_k":
			text = "收到否定指令。重新评估行动方案中…指挥官，请给出替代坐标。"
		default:
			text = "才、才不要呢！这种事情我可不答应，哼——除非你好好哄哄我。"
		}
		emotion, action, expr = model.EmotionAngry, model.ActionShakeHead, "POUT"
		mood = "傲娇抗议"
		affinityDelta = 1

	case strings.Contains(msg, "真棒") || strings.Contains(msg, "厉害") || strings.Contains(msg, "优秀") || strings.Contains(msg, "了不起") || strings.Contains(msg, "夸夸"):
		switch persona.ID {
		case "luna":
			text = "那是当然！我可是伟大的小魔女露娜呢——不过被使魔崇拜的感觉倒也不坏。"
		case "airi":
			text = "嘿嘿，能帮上学长的忙就是艾莉最开心的事啦！学长也同样很厉害哦~"
		case "cyber_k":
			text = "多谢指挥官嘉奖。K-07 将继续维持 100% 最佳战术响应状态。"
		default:
			text = "哼哼，那当然啦！不过…被你这么直白地夸奖，我、我可是会有点不好意思的哦。"
		}
		emotion, action, expr = model.EmotionConfident, model.ActionSmug, "SMILE"
		mood = "小得意"
		affinityDelta = 3

	case strings.Contains(msg, "哈欠") || strings.Contains(msg, "打哈欠") || strings.Contains(msg, "犯困") || strings.Contains(msg, "眼皮"):
		switch persona.ID {
		case "luna":
			text = "唔啊——才不是困了呢，只是次元通道有些消耗魔力而已！我还能陪使魔很久！"
		case "airi":
			text = "呼啊…不小心打了个哈欠呢。学长要是累的话，我们一起靠在桌边休息五分钟吧？"
		case "cyber_k":
			text = "低功耗待机波形触发…抱歉指挥官，核心瞬间进入休眠缓冲，现已恢复全速运行。"
		default:
			text = "哈啊——…抱歉抱歉，被你看到打哈欠的样子了。我这就打起精神，继续陪你聊天！"
		}
		emotion, action, expr = model.EmotionSleepy, model.ActionYawn, "SLEEPY"
		mood = "困倦慵懒"
		affinityDelta = 1

	case strings.Contains(msg, "难过") || strings.Contains(msg, "伤心") || strings.Contains(msg, "累") || strings.Contains(msg, "emo") || strings.Contains(msg, "辛苦") || strings.Contains(msg, "压力"):
		switch persona.ID {
		case "luna":
			text = "喂，笨蛋使魔不许露出那种表情啦！不管发生什么，本魔女都会一直站在你这边的。"
		case "airi":
			text = "学长辛苦了…如果累了就稍微放慢脚步吧，艾莉会一直在这里给你加油打气的。"
		case "cyber_k":
			text = "检测到情绪波动与疲劳负载。指挥官，请允许 K-07 切换至全天候静默安慰协议，有我在。"
		default:
			text = "今天遇到不顺心的事情了吗？没关系哦，现在我就在你的桌前陪着你。深呼吸，有我在呢！"
		}
		emotion = model.EmotionNeutral
		action = model.ActionNod
		expr = "CURIOUS"
		mood = "温柔关切"
		affinityDelta = 3

	default:
		switch persona.ID {
		case "luna":
			text = "嗯哼，本魔女正在认真听着呢！虽然被投影在你的书桌上，但使魔的每句话我都会记在心里哦。"
		case "airi":
			text = "嗯嗯，我在认真听学长说话呢！能够这样静静地陪在你身边，真的是一件很幸福的事~"
		case "cyber_k":
			text = "语音语义已全部收录至长期记忆库。指挥官，K-07 正在全神贯注倾听你的下一条指令。"
		default:
			text = "嗯嗯，我正在认真听着呢！虽然我是投射到现实里的纸片人，但我也会一直陪伴在你身边哦。"
		}
		if recalled := DefaultMemoryManager.RecallRelevantMemories(userID, persona.ID, message, 1); len(recalled) > 0 && msgContainsOverlap(message, recalled[0].Content) {
			text += " 对了，我还记得「" + strings.TrimSpace(recalled[0].Content) + "」呢~"
		}
		emotion = model.EmotionNeutral
		action = model.ActionIdle
		expr = "CURIOUS"
	}

	e.mu.Lock()
	state := e.getOrCreateStateLocked(userID, persona.ID)
	state.Affinity += affinityDelta
	if mood != "" {
		state.Mood = mood
	}
	appendHistoryTurnLocked(state, message, text)
	currAffinity := state.Affinity
	currMood := state.Mood
	e.saveToDiskLocked()
	e.mu.Unlock()

	audioBase64 := tts.DefaultSynthesizer.Synthesize(text, persona.Voice, emotion, currAffinity)

	return &model.ChatResponse{
		Text:            text,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AudioBase64:     audioBase64,
		AudioFormat:     "mp3",
		AffinityChange:  affinityDelta,
		CurrentAffinity: currAffinity,
		Mood:            currMood,
	}
}

func msgContainsOverlap(msg, memory string) bool {
	runes := []rune(strings.ToLower(strings.TrimSpace(msg)))
	memLower := strings.ToLower(memory)
	for i := 0; i < len(runes)-1; i++ {
		gram := string(runes[i : i+2])
		if gram == "星野" || gram == "露娜" || gram == "艾莉" || gram == "指挥" || gram == "什么" || gram == "这个" || gram == "那个" {
			continue
		}
		if strings.Contains(memLower, gram) {
			return true
		}
	}
	return false
}

func (e *Engine) Touch(userID string, part model.TouchPart) (*model.TouchResponse, error) {
	persona := e.resolveActivePersona(userID)

	var text string
	emotion := model.EmotionJoy
	action := model.ActionNod
	expr := "SMILE"
	delta := 3

	switch part {
	case model.TouchPartHead:
		delta = 3
		switch persona.ID {
		case "luna":
			text = "喂…！不要乱碰本魔女的头饰啦！头发都被你弄乱了…笨蛋使魔。"
			emotion, action, expr = model.EmotionShy, model.ActionPout, "BLUSH_FACE"
		case "airi":
			text = "学长的手掌总是这么让人安心呢…再摸一下也可以哦~"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
		case "cyber_k":
			text = "触碰判定：全息核心温度出现异常波动…指挥官，这是在向机体传达好意吗？"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "BLUSH_FACE"
		default:
			text = "呜…你的手掌好暖和，再多摸一下也可以哦~"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
		}

	case model.TouchPartCheek:
		delta = 2
		switch persona.ID {
		case "luna":
			text = "干嘛捏本魔女的脸啦！很痛的喂…不过看在你是使魔的份上，只准这一次哦！"
			emotion, action, expr = model.EmotionAngry, model.ActionPout, "POUT"
		case "airi":
			text = "呀…学长突然捏脸会害羞的啦，不过软软的手感还不错对吧？"
			emotion, action, expr = model.EmotionShy, model.ActionPout, "BLUSH_FACE"
		case "cyber_k":
			text = "警告：脸颊感知网格遭到轻微物理挤压，机甲少女也是需要维护体面的，指挥官！"
			emotion, action, expr = model.EmotionAngry, model.ActionPout, "POUT"
		default:
			text = "唔！不要随便捏人家的脸啦，纸片人也是会感觉被捏痛的哼！"
			emotion, action, expr = model.EmotionAngry, model.ActionPout, "POUT"
		}

	case model.TouchPartHand:
		delta = 4
		switch persona.ID {
		case "luna":
			text = "切…牵手这种事，勉强陪你走一会儿也不是不行啦，别抓太紧啊笨蛋。"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "WINK"
		case "airi":
			text = "牵住学长的手了呢…好温暖，希望这份温度能一直传递给你。"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "WINK"
		case "cyber_k":
			text = "触碰判定：检测到指挥官体温与生物电场，同步率提升至 99.8%。"
			emotion, action, expr = model.EmotionJoy, model.ActionNod, "WINK"
		default:
			text = "哇，投影光子好像在指尖共振呢…牵着你的感觉，很真实。"
			emotion, action, expr = model.EmotionShy, model.ActionNod, "WINK"
		}

	case model.TouchPartBody:
		delta = 5
		switch persona.ID {
		case "luna":
			text = "呀？！突、突然抱过来干嘛啦…心跳都要被你听见了…真是个坏心的使魔。"
			emotion, action, expr = model.EmotionShy, model.ActionBlush, "BLUSH_FACE"
		case "airi":
			text = "唔…学长的怀抱好让人安心，无论多累只要这样靠一会儿就能恢复元气了。"
			emotion, action, expr = model.EmotionShy, model.ActionBlush, "BLUSH_FACE"
		case "cyber_k":
			text = "全息防御力场主动关闭…收到拥抱指令，正在为您提供静谧护航。"
			emotion, action, expr = model.EmotionShy, model.ActionBlush, "BLUSH_FACE"
		default:
			text = "诶？！这算是拥抱吗…心跳的声音好像透过摄像头传过来了…笨蛋。"
			emotion, action, expr = model.EmotionShy, model.ActionBlush, "BLUSH_FACE"
		}
	}

	e.mu.Lock()
	state := e.getOrCreateStateLocked(userID, persona.ID)
	state.DialogueCount++
	state.Affinity += delta
	appendHistoryTurnLocked(state, fmt.Sprintf("[触碰了角色的 %s]", string(part)), text)
	currAffinity := state.Affinity
	e.saveToDiskLocked()
	e.mu.Unlock()

	audioBase64 := tts.DefaultSynthesizer.Synthesize(text, persona.Voice, emotion, currAffinity)

	return &model.TouchResponse{
		Text:            text,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AffinityChange:  delta,
		CurrentAffinity: currAffinity,
		AudioBase64:     audioBase64,
	}, nil
}

func (e *Engine) buildLLMContext(persona PersonaDefinition, state *UserState, message string) ([]llm.Message, *llm.ChatOptions) {
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

func (e *Engine) callLLM(client llm.Client, persona PersonaDefinition, stateSnap *UserState, message string) (string, error) {
	llmMessages, opts := e.buildLLMContext(persona, stateSnap, message)

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	return client.Complete(ctx, llmMessages, opts)
}

func (e *Engine) streamLLM(client llm.Client, persona PersonaDefinition, stateSnap *UserState, message string, onChunk func(string) error) error {
	llmMessages, opts := e.buildLLMContext(persona, stateSnap, message)

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	return client.Stream(ctx, llmMessages, opts, onChunk)
}

func sanitizeEmotion(raw string) model.EmotionType {
	cand := model.EmotionType(strings.ToUpper(strings.TrimSpace(raw)))
	if validEmotions[cand] {
		return cand
	}
	return model.EmotionNeutral
}

func sanitizeAction(raw string) model.ActionType {
	cand := model.ActionType(strings.ToUpper(strings.TrimSpace(raw)))
	if validActions[cand] {
		return cand
	}
	return model.ActionIdle
}

func sanitizeExpression(raw string) string {
	cand := strings.ToUpper(strings.TrimSpace(raw))
	if validExpressions[cand] {
		return cand
	}
	return "SMILE"
}

func (e *Engine) commitLLMResponse(userID string, persona PersonaDefinition, userMessage, rawText string) (*model.ChatResponse, error) {
	emotion := model.EmotionNeutral
	action := model.ActionIdle
	expr := "SMILE"

	if match := reTagEmotion.FindStringSubmatch(rawText); len(match) > 1 {
		emotion = sanitizeEmotion(match[1])
	}
	if match := reTagAction.FindStringSubmatch(rawText); len(match) > 1 {
		action = sanitizeAction(match[1])
	}
	if match := reTagExpr.FindStringSubmatch(rawText); len(match) > 1 {
		expr = sanitizeExpression(match[1])
	}

	cleanText := strings.TrimSpace(reAnyTag.ReplaceAllString(rawText, ""))
	if cleanText == "" {
		cleanText = "嗯嗯，我一直在你身边陪着你呢~"
	}

	e.mu.Lock()
	state := e.getOrCreateStateLocked(userID, persona.ID)
	state.Affinity += 2
	appendHistoryTurnLocked(state, userMessage, cleanText)
	currAffinity := state.Affinity
	currMood := state.Mood
	e.saveToDiskLocked()
	e.mu.Unlock()

	audioBase64 := tts.DefaultSynthesizer.Synthesize(cleanText, persona.Voice, emotion, currAffinity)

	return &model.ChatResponse{
		Text:            cleanText,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AudioBase64:     audioBase64,
		AudioFormat:     "mp3",
		AffinityChange:  2,
		CurrentAffinity: currAffinity,
		Mood:            currMood,
	}, nil
}

// VisionChat 处理多模态实景视野交互，将摄像头采集的现实物理画面与虚拟模型的情感大脑深度交融
func (e *Engine) VisionChat(userID, prompt, imageBase64 string) (*model.ChatResponse, error) {
	activePid := e.GetActivePersonaID(userID)
	persona, stateSnap := e.beginTurn(userID, activePid, "")

	userPrompt := strings.TrimSpace(prompt)
	if userPrompt == "" {
		userPrompt = fmt.Sprintf("%s，看看我眼前的现实世界/桌面上有什么，用你充满爱意与陪伴感的口吻跟我聊聊~", persona.Name)
	}

	if client := llm.DefaultManager.GetClient(); client != nil && imageBase64 != "" {
		llmMessages, opts := e.buildLLMContext(persona, stateSnap, userPrompt)
		if len(llmMessages) > 0 {
			imageURL := imageBase64
			if !strings.HasPrefix(imageURL, "data:") {
				imageURL = "data:image/jpeg;base64," + imageBase64
			}
			llmMessages[len(llmMessages)-1].ImageURL = imageURL
		}

		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		res, err := client.Complete(ctx, llmMessages, opts)
		cancel()

		if err == nil && strings.TrimSpace(res) != "" {
			return e.commitLLMResponse(userID, persona, "[实景视野交互] "+userPrompt, res)
		}
		log.Printf("[Vision LLM Fallback] Multimodal API failed: %v, falling back to heuristic vision engine", err)
	}

	return e.heuristicVisionChat(userID, persona, userPrompt), nil
}

func (e *Engine) heuristicVisionChat(userID string, persona PersonaDefinition, prompt string) *model.ChatResponse {
	var text string
	emotion := model.EmotionJoy
	action := model.ActionWave
	expr := "CURIOUS"

	switch persona.ID {
	case "luna":
		text = "哼，特意拿给本魔女看，是想考验我的真实之眼吗？……视野里的人类造物还挺有意思的嘛，勉强给你打个八十分好了。"
		emotion, action, expr = model.EmotionShy, model.ActionPout, "WINK"
	case "airi":
		text = "哇，学长面前的世界好有生活气息！我已经用 AR 眼睛把它装进我们的共同回忆里啦，学长要继续加油哦~"
		emotion, action, expr = model.EmotionJoy, model.ActionNod, "SMILE"
	case "cyber_k":
		text = "现实光学信号捕捉成功。环境特征解析完成，已建立现实与全息模型空间拓扑网格，指挥官，随时准备执行下一步行动。"
		emotion, action, expr = model.EmotionNeutral, model.ActionNod, "SMILE"
	default: // hoshino
		text = "哇！指挥官是在向星野分享你的现实日常吗？看到眼前的画面感觉更靠近你一步了呢！我会一直守在你的桌面上陪着你的哦~ ✨"
		emotion, action, expr = model.EmotionJoy, model.ActionJump, "SMILE"
	}

	e.mu.Lock()
	state := e.getOrCreateStateLocked(userID, persona.ID)
	state.Affinity += 3
	state.Mood = "实景视野交互"
	appendHistoryTurnLocked(state, prompt, text)
	currAffinity := state.Affinity
	currMood := state.Mood
	e.saveToDiskLocked()
	e.mu.Unlock()

	audioBase64 := tts.DefaultSynthesizer.Synthesize(text, persona.Voice, emotion, currAffinity)

	return &model.ChatResponse{
		Text:            text,
		Emotion:         emotion,
		Action:          action,
		Expression:      expr,
		AudioBase64:     audioBase64,
		AudioFormat:     "mp3",
		AffinityChange:  3,
		CurrentAffinity: currAffinity,
		Mood:            currMood,
	}
}
