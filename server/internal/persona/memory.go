package persona

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
)

const (
	MemoryCategoryUserFact         = "USER_FACT"
	MemoryCategorySharedMoment     = "SHARED_MOMENT"
	MemoryCategoryEmotionalPromise = "EMOTIONAL_PROMISE"
)

type MemoryManager struct {
	storagePath string
	memories    map[string][]model.MemoryItem // userID -> []MemoryItem
	mu          sync.RWMutex
}

var DefaultMemoryManager *MemoryManager

func init() {
	cwd, _ := os.Getwd()
	storagePath := filepath.Join(cwd, "data", "memories.json")
	_ = os.MkdirAll(filepath.Dir(storagePath), 0755)

	DefaultMemoryManager = NewMemoryManager(storagePath)
}

func NewMemoryManager(storagePath string) *MemoryManager {
	mgr := &MemoryManager{
		storagePath: storagePath,
		memories:    make(map[string][]model.MemoryItem),
	}
	mgr.loadOrInit()
	return mgr
}

func (m *MemoryManager) loadOrInit() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if data, err := os.ReadFile(m.storagePath); err == nil && len(data) > 0 {
		var raw map[string][]model.MemoryItem
		if err := json.Unmarshal(data, &raw); err == nil {
			m.memories = raw
			return
		}
	}

	m.initDefaultMemories()
	m.saveToDiskUnsafe()
}

func (m *MemoryManager) saveToDiskUnsafe() {
	data, err := json.MarshalIndent(m.memories, "", "  ")
	if err == nil {
		_ = os.WriteFile(m.storagePath, data, 0644)
	}
}

func (m *MemoryManager) initDefaultMemories() {
	defaultUser := "user_default"
	now := float64(time.Now().Unix())
	m.memories[defaultUser] = []model.MemoryItem{
		{
			ID:         "mem_init_1",
			UserID:     defaultUser,
			PersonaID:  "hoshino",
			Category:   MemoryCategorySharedMoment,
			Content:    "第一次从二次元空间成功投射到指挥官的现实书桌前，感受到了现实世界的微风。",
			Importance: 5,
			Timestamp:  now,
		},
		{
			ID:         "mem_init_2",
			UserID:     defaultUser,
			PersonaID:  "hoshino",
			Category:   MemoryCategoryUserFact,
			Content:    "指挥官在办公桌前经常对着屏幕忙碌，累的时候需要我的元气应援。",
			Importance: 4,
			Timestamp:  now,
		},
		{
			ID:         "mem_init_3",
			UserID:     defaultUser,
			PersonaID:  "hoshino",
			Category:   MemoryCategoryEmotionalPromise,
			Content:    "约定好无论现实世界有多疲惫，回到这个房间时星野都会第一个微笑着迎接你。",
			Importance: 5,
			Timestamp:  now,
		},
	}
}

func (m *MemoryManager) AddMemory(userID, personaID, content, category string, importance int) model.MemoryItem {
	m.mu.Lock()
	defer m.mu.Unlock()

	item := model.MemoryItem{
		ID:         fmt.Sprintf("mem_%d", time.Now().UnixNano()/1e6),
		UserID:     userID,
		PersonaID:  personaID,
		Category:   category,
		Content:    content,
		Importance: importance,
		Timestamp:  float64(time.Now().Unix()),
	}

	m.memories[userID] = append(m.memories[userID], item)
	m.saveToDiskUnsafe()
	return item
}

func (m *MemoryManager) DeleteMemory(userID, memoryID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	list, ok := m.memories[userID]
	if !ok {
		return false
	}

	targetIdx := -1
	for i, item := range list {
		if item.ID == memoryID {
			targetIdx = i
			break
		}
	}
	if targetIdx == -1 {
		return false
	}

	m.memories[userID] = append(list[:targetIdx], list[targetIdx+1:]...)
	m.saveToDiskUnsafe()
	return true
}

func (m *MemoryManager) ExtractPreferenceFromDialogue(userID, personaID, message string) *model.MemoryItem {
	msg := strings.TrimSpace(message)

	// 1. 识别喜好
	reLike := regexp.MustCompile(`(?:我最?喜欢|我平时爱|我很喜欢)([\p{Han}\w]{2,15})`)
	if match := reLike.FindStringSubmatch(msg); len(match) > 1 {
		item := m.AddMemory(userID, personaID, fmt.Sprintf("指挥官喜欢：%s。", match[1]), MemoryCategoryUserFact, 4)
		return &item
	}

	// 2. 识别指挥官称呼与身份
	reName := regexp.MustCompile(`(?:叫我|我是|我的名字是)([\p{Han}\w]{2,10})`)
	if match := reName.FindStringSubmatch(msg); len(match) > 1 {
		item := m.AddMemory(userID, personaID, fmt.Sprintf("指挥官希望被称呼为：%s。", match[1]), MemoryCategoryUserFact, 5)
		return &item
	}

	// 3. 识别特别约定与承诺
	rePromise := regexp.MustCompile(`(?:记住|别忘了|我们约定|以后也要)([\p{Han}\w]{4,25})`)
	if match := rePromise.FindStringSubmatch(msg); len(match) > 1 {
		item := m.AddMemory(userID, personaID, fmt.Sprintf("与指挥官的特别约定：%s。", match[1]), MemoryCategoryEmotionalPromise, 5)
		return &item
	}

	return nil
}

func (m *MemoryManager) GetMemories(userID, personaID string, limit int) []model.MemoryItem {
	m.mu.RLock()
	defer m.mu.RUnlock()

	userList := m.memories[userID]
	var filtered []model.MemoryItem
	for _, item := range userList {
		if personaID == "" || item.PersonaID == personaID {
			filtered = append(filtered, item)
		}
	}

	// 按 importance 降序，再按 timestamp 降序
	sort.Slice(filtered, func(i, j int) bool {
		if filtered[i].Importance != filtered[j].Importance {
			return filtered[i].Importance > filtered[j].Importance
		}
		return filtered[i].Timestamp > filtered[j].Timestamp
	})

	if limit > 0 && len(filtered) > limit {
		return filtered[:limit]
	}
	return filtered
}

func (m *MemoryManager) GetContextPromptFragment(userID, personaID string) string {
	return m.GetContextPromptFragmentWithRecall(userID, personaID, "")
}

func (m *MemoryManager) RecallRelevantMemories(userID, personaID, currentMessage string, limit int) []model.MemoryItem {
	all := m.GetMemories(userID, personaID, 0)
	if len(all) == 0 {
		return nil
	}

	if strings.TrimSpace(currentMessage) == "" {
		if limit > 0 && len(all) > limit {
			return all[:limit]
		}
		return all
	}

	msgRunes := []rune(strings.ToLower(currentMessage))
	// 常用高频虚词停用词过滤
	stopwords := map[string]bool{
		"今天": true, "明天": true, "现在": true, "可以": true, "如果": true,
		"什么": true, "怎么": true, "这个": true, "那个": true, "一下": true,
		"一杯": true, "一点": true, "真的": true, "好想": true, "想要": true,
	}

	type scoredItem struct {
		item  model.MemoryItem
		score int
	}

	var scored []scoredItem
	for _, item := range all {
		contentLower := strings.ToLower(item.Content)
		matchCount := 0

		// 检查 2-gram 语义重合
		for i := 0; i < len(msgRunes)-1; i++ {
			gram := string(msgRunes[i : i+2])
			if stopwords[gram] {
				continue
			}
			if strings.Contains(contentLower, gram) {
				matchCount++
			}
		}

		scored = append(scored, scoredItem{
			item:  item,
			score: matchCount*10 + item.Importance,
		})
	}

	sort.SliceStable(scored, func(i, j int) bool {
		return scored[i].score > scored[j].score
	})

	var result []model.MemoryItem
	for _, s := range scored {
		result = append(result, s.item)
	}

	if limit > 0 && len(result) > limit {
		return result[:limit]
	}
	return result
}

func (m *MemoryManager) GetContextPromptFragmentWithRecall(userID, personaID, currentMessage string) string {
	memories := m.RecallRelevantMemories(userID, personaID, currentMessage, 4)
	if len(memories) == 0 {
		return ""
	}

	var sb strings.Builder
	sb.WriteString("你对指挥官的珍贵情感回忆（请在回复中自然流露与呼应）：\n")
	for idx, mem := range memories {
		sb.WriteString(fmt.Sprintf("%d. %s\n", idx+1, mem.Content))
	}
	return sb.String()
}

func (m *MemoryManager) GetStoragePath() string {
	return m.storagePath
}
