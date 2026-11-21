package llm

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type OpenAICompatibleClient struct {
	baseURL    string
	apiKey     string
	model      string
	httpClient *http.Client
}

func NewOpenAICompatibleClient(baseURL, apiKey, model string) *OpenAICompatibleClient {
	cleanBaseURL := strings.TrimRight(baseURL, "/")
	if !strings.HasSuffix(cleanBaseURL, "/v1") {
		cleanBaseURL += "/v1"
	}

	return &OpenAICompatibleClient{
		baseURL: cleanBaseURL,
		apiKey:  apiKey,
		model:   model,
		httpClient: &http.Client{
			Timeout: 45 * time.Second,
		},
	}
}

func (c *OpenAICompatibleClient) Complete(ctx context.Context, messages []Message, opts *ChatOptions) (string, error) {
	temp := float32(0.7)
	maxTokens := 300
	if opts != nil {
		if opts.Temperature > 0 {
			temp = opts.Temperature
		}
		if opts.MaxTokens > 0 {
			maxTokens = opts.MaxTokens
		}
	}

	payload := map[string]interface{}{
		"model":       c.model,
		"messages":    messages,
		"temperature": temp,
		"max_tokens":  maxTokens,
	}

	bodyBytes, err := json.Marshal(payload)
	if err != nil {
		return "", fmt.Errorf("marshal payload failed: %w", err)
	}

	reqURL := c.baseURL + "/chat/completions"
	req, err := http.NewRequestWithContext(ctx, "POST", reqURL, bytes.NewBuffer(bodyBytes))
	if err != nil {
		return "", fmt.Errorf("create request failed: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("llm api error: code=%d, body=%s", resp.StatusCode, string(respBody))
	}

	var res struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return "", fmt.Errorf("decode response failed: %w", err)
	}

	if len(res.Choices) == 0 {
		return "", fmt.Errorf("empty choices from llm api")
	}

	return strings.TrimSpace(res.Choices[0].Message.Content), nil
}

func (c *OpenAICompatibleClient) Stream(ctx context.Context, messages []Message, opts *ChatOptions, onChunk func(chunk string) error) error {
	temp := float32(0.7)
	maxTokens := 300
	if opts != nil {
		if opts.Temperature > 0 {
			temp = opts.Temperature
		}
		if opts.MaxTokens > 0 {
			maxTokens = opts.MaxTokens
		}
	}

	payload := map[string]interface{}{
		"model":       c.model,
		"messages":    messages,
		"temperature": temp,
		"max_tokens":  maxTokens,
		"stream":      true,
	}

	bodyBytes, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal payload failed: %w", err)
	}

	reqURL := c.baseURL + "/chat/completions"
	req, err := http.NewRequestWithContext(ctx, "POST", reqURL, bytes.NewBuffer(bodyBytes))
	if err != nil {
		return fmt.Errorf("create request failed: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("llm api error: code=%d, body=%s", resp.StatusCode, string(respBody))
	}

	reader := bufio.NewReader(resp.Body)
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			if err == io.EOF {
				break
			}
			return fmt.Errorf("read stream line failed: %w", err)
		}

		line = strings.TrimSpace(line)
		if line == "" || !strings.HasPrefix(line, "data:") {
			continue
		}

		dataPayload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if dataPayload == "[DONE]" {
			break
		}

		var chunk struct {
			Choices []struct {
				Delta struct {
					Content string `json:"content"`
				} `json:"delta"`
			} `json:"choices"`
		}

		if err := json.Unmarshal([]byte(dataPayload), &chunk); err == nil {
			if len(chunk.Choices) > 0 && chunk.Choices[0].Delta.Content != "" {
				if err := onChunk(chunk.Choices[0].Delta.Content); err != nil {
					return err
				}
			}
		}
	}

	return nil
}

func (c *OpenAICompatibleClient) TestConnection(ctx context.Context) error {
	testMessages := []Message{
		{Role: RoleUser, Content: "ping"},
	}
	opts := &ChatOptions{MaxTokens: 5, Temperature: 0.1}
	_, err := c.Complete(ctx, testMessages, opts)
	return err
}
