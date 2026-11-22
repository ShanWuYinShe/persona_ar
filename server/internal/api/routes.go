package api

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
)

func RegisterRoutes(r *gin.Engine) {
	// 健康检查探针
	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":  "healthy",
			"service": "persona-ar-brain-go",
			"version": "1.0.0",
		})
	})

	apiV1 := r.Group("/api/v1")
	{
		// 1. 对话交互
		apiV1.POST("/chat/interact", handleChatInteract)

		// 2. 语音/对讲交互
		apiV1.POST("/voice/interact", handleVoiceInteract)

		// 3. 触碰互动
		apiV1.POST("/persona/touch", handlePersonaTouch)

		// 4. 角色状态与记忆
		apiV1.GET("/persona/status", handlePersonaStatus)

		// 5. 切换当前角色伙伴
		apiV1.POST("/persona/switch", handlePersonaSwitch)

		// 6. 重置当前角色记忆与好感
		apiV1.POST("/persona/reset", handlePersonaReset)

		// 7. 实景多模态视觉交互 (摄像头物理画面交融)
		apiV1.POST("/vision/interact", handleVisionInteract)
	}

	// 注册商城工坊、大模型配置、语音识别 (ASR) 与全双工 WebSocket
	RegisterStoreRoutes(apiV1)
	RegisterLLMRoutes(apiV1)
	RegisterASRRoutes(apiV1)
	RegisterWebSocketRoutes(apiV1)
}

func handleChatInteract(c *gin.Context) {
	var req model.ChatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	userID := req.UserID
	if userID == "" {
		userID = "user_default"
	}

	resp, err := persona.DefaultEngine.Chat(userID, req.Message)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"detail": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}

func handleVoiceInteract(c *gin.Context) {
	var req model.VoiceInteractRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	userID := req.UserID
	if userID == "" {
		userID = "user_default"
	}

	msg := "指挥官在吗？"
	if req.TextPrompt != nil && *req.TextPrompt != "" {
		msg = *req.TextPrompt
	} else if req.AudioBase64 != nil && *req.AudioBase64 != "" {
		msg = "（对讲机传来指挥官温和的呼吸与声音）"
	}

	resp, err := persona.DefaultEngine.Chat(userID, msg)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"detail": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}

func handlePersonaTouch(c *gin.Context) {
	var req model.TouchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	userID := req.UserID
	if userID == "" {
		userID = "user_default"
	}

	resp, err := persona.DefaultEngine.Touch(userID, req.Part)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"detail": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}

func handlePersonaStatus(c *gin.Context) {
	userID := c.DefaultQuery("user_id", "user_default")
	activePid := persona.DefaultEngine.GetActivePersonaID(userID)
	pDef, ok := persona.PersonaRegistry[activePid]
	if !ok {
		pDef = persona.PersonaRegistry["hoshino"]
	}

	state := persona.DefaultEngine.GetUserState(userID, activePid)

	status := model.PersonaStatus{
		Name:          pDef.Name,
		RoleDesc:      pDef.RoleDesc,
		Affinity:      state.Affinity,
		AffinityLevel: state.GetAffinityLevel(),
		Mood:          state.Mood,
		DialogueCount: state.DialogueCount,
		RecentMemories: []string{
			"刚刚从次元裂缝被投影到这个现实空间中",
			"在好奇地打量你的房间和身边的环境",
		},
	}

	c.JSON(http.StatusOK, status)
}

func handlePersonaSwitch(c *gin.Context) {
	var req model.SwitchPersonaRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	userID := req.UserID
	if userID == "" {
		userID = "user_default"
	}

	if persona.DefaultEngine.SwitchUserPersona(userID, req.PersonaID) {
		c.JSON(http.StatusOK, gin.H{
			"status":          "ok",
			"current_persona": req.PersonaID,
		})
	} else {
		c.JSON(http.StatusNotFound, gin.H{"detail": "未找到指定角色模型"})
	}
}

func handlePersonaReset(c *gin.Context) {
	userID := c.DefaultQuery("user_id", "user_default")
	persona.DefaultEngine.ResetUserState(userID)

	c.JSON(http.StatusOK, gin.H{
		"status":  "ok",
		"message": "已重置好感度与对话记忆",
	})
}

func handleVisionInteract(c *gin.Context) {
	var req model.VisionInteractRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	userID := req.UserID
	if userID == "" {
		userID = "user_default"
	}

	resp, err := persona.DefaultEngine.VisionChat(userID, req.Message, req.ImageBase64)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"detail": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}
