package asr

import (
	"context"
	"fmt"
	"math/rand"
	"time"
)

// HeuristicMockProvider 内置智能启发式 ASR 降级提供商
type HeuristicMockProvider struct {
	config Config
}

func NewHeuristicMockProvider(cfg Config) *HeuristicMockProvider {
	return &HeuristicMockProvider{config: cfg}
}

func (p *HeuristicMockProvider) Name() string {
	return "heuristic-mock"
}

func (p *HeuristicMockProvider) Transcribe(ctx context.Context, audioData []byte, filename string) (string, error) {
	if len(audioData) == 0 {
		return "", fmt.Errorf("audio data is empty")
	}

	// 模拟毫秒级轻微识别计算耗时
	select {
	case <-time.After(50 * time.Millisecond):
	case <-ctx.Done():
		return "", ctx.Err()
	}

	size := len(audioData)
	// 根据音频大小与特征，映射到丰富且自然的日常交互语义
	shortPhrases := []string{
		"星野，你在听吗？",
		"今天过得怎么样？",
		"看到你真开心！",
		"好久不见呀~",
	}

	mediumPhrases := []string{
		"今天在现实世界忙了一整天，想和你在书桌前聊聊天呢。",
		"你今天的心情看起来很棒，有什么开心的事情吗？",
		"我平时最喜欢喝热奶茶，你呢？",
		"今天工作稍微有点累，需要你的治愈应援~",
	}

	longPhrases := []string{
		"刚刚在整理桌面的东西，一抬头看到你在身边微笑，突然觉得今天特别安心。",
		"我们之前约好的，无论现实世界多忙碌，每天都要互相问候哦！",
		"现实世界的微风很舒服，感觉你就像真的站在我身边一样生动真实。",
	}

	var candidates []string
	if size < 32000 { // < ~1s (16kHz 16bit PCM is 32kB/s)
		candidates = shortPhrases
	} else if size < 96000 { // ~1s - 3s
		candidates = mediumPhrases
	} else {
		candidates = longPhrases
	}

	seed := int64(size) + time.Now().UnixNano()%100
	r := rand.New(rand.NewSource(seed))
	return candidates[r.Intn(len(candidates))], nil
}
