package api

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/ShanWuYinShe/persona_ar/server/internal/llm"
)

func RegisterLLMRoutes(rg *gin.RouterGroup) {
	group := rg.Group("/llm")
	{
		group.GET("/config", handleGetLLMConfig)
		group.POST("/config", handleSetLLMConfig)
		group.POST("/test", handleTestLLMConnection)
	}
}

func handleGetLLMConfig(c *gin.Context) {
	cfg := llm.DefaultManager.GetConfig()
	c.JSON(http.StatusOK, cfg)
}

func handleSetLLMConfig(c *gin.Context) {
	var req llm.ProviderConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	llm.DefaultManager.Configure(req)
	c.JSON(http.StatusOK, gin.H{
		"status":  "ok",
		"message": "大模型服务配置已生效",
		"config":  llm.DefaultManager.GetConfig(),
	})
}

func handleTestLLMConnection(c *gin.Context) {
	var req llm.ProviderConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		// 如果未传参，则测试当前配置
		req = llm.DefaultManager.GetConfig()
	}

	start := time.Now()
	ctx, cancel := context.WithTimeout(c.Request.Context(), 8*time.Second)
	defer cancel()

	err := llm.DefaultManager.TestConnection(ctx, req)
	latencyMs := time.Since(start).Milliseconds()

	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{
			"status":     "error",
			"message":    err.Error(),
			"latency_ms": latencyMs,
		})
		return
	}

	mode := "external"
	msg := "大模型 API 连通性测试成功"
	if req.Provider == "" || req.Provider == "mock" || req.Provider == "builtin" || req.Provider == "heuristic" || (req.APIKey == "" && req.Provider != "ollama") {
		mode = "heuristic"
		msg = "当前处于内置拟人情感引擎模式（无需外部 API Key）"
	}

	c.JSON(http.StatusOK, gin.H{
		"status":     "ok",
		"mode":       mode,
		"message":    msg,
		"latency_ms": latencyMs,
	})
}
