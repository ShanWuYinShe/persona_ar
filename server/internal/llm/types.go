package llm

import "context"

type Role string

const (
	RoleSystem    Role = "system"
	RoleUser      Role = "user"
	RoleAssistant Role = "assistant"
)

type Message struct {
	Role    Role   `json:"role"`
	Content string `json:"content"`
}

type ChatOptions struct {
	Temperature float32 `json:"temperature,omitempty"`
	MaxTokens   int     `json:"max_tokens,omitempty"`
}

type Client interface {
	Complete(ctx context.Context, messages []Message, opts *ChatOptions) (string, error)
	Stream(ctx context.Context, messages []Message, opts *ChatOptions, onChunk func(chunk string) error) error
	TestConnection(ctx context.Context) error
}

type ProviderConfig struct {
	Provider string `json:"provider"`
	BaseURL  string `json:"base_url"`
	APIKey   string `json:"api_key"`
	Model    string `json:"model"`
}
