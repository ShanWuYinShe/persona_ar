package llm

import (
	"context"
	"strings"
	"sync"
	"time"
)

type MockClient struct {
	customResponse string
	shouldFail     bool
	mu             sync.RWMutex
}

func NewMockClient() *MockClient {
	return &MockClient{
		customResponse: "",
		shouldFail:     false,
	}
}

func (m *MockClient) SetResponse(resp string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.customResponse = resp
}

func (m *MockClient) SetFailure(fail bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.shouldFail = fail
}

func (m *MockClient) Complete(ctx context.Context, messages []Message, opts *ChatOptions) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if m.shouldFail {
		return "", context.DeadlineExceeded
	}

	if m.customResponse != "" {
		return m.customResponse, nil
	}

	lastMsg := ""
	if len(messages) > 0 {
		lastMsg = messages[len(messages)-1].Content
	}

	return "【大模型思索后微笑着倾听】我收到你的心语了：“" + lastMsg + "”，无论在哪个维度，我都在这里陪伴着你哦！ <emotion:JOY> <action:WAVE> <expr:SMILE>", nil
}

func (m *MockClient) Stream(ctx context.Context, messages []Message, opts *ChatOptions, onChunk func(chunk string) error) error {
	resp, err := m.Complete(ctx, messages, opts)
	if err != nil {
		return err
	}

	// 模拟流式分词返回
	words := strings.Fields(resp)
	for _, word := range words {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			if err := onChunk(word + " "); err != nil {
				return err
			}
			time.Sleep(10 * time.Millisecond)
		}
	}
	return nil
}

func (m *MockClient) TestConnection(ctx context.Context) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.shouldFail {
		return context.DeadlineExceeded
	}
	return nil
}
