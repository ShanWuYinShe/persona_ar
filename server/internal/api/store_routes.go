package api

import (
	"fmt"
	"net/http"
	"strings"
	"sync"

	"github.com/gin-gonic/gin"

	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
	"github.com/ShanWuYinShe/persona_ar/server/internal/persona"
)

var (
	storeModels = []model.PersonaModelManifest{
		{
			ID:              "hoshino",
			Name:            "星野 (Hoshino)",
			Version:         "1.2.0",
			Author:          "Persona Studio Official",
			Description:     "投射在现实中的二次元傲娇元气少女，心软口硬，对现实世界充满好奇与依恋。",
			Tags:            []string{"元气", "傲娇", "双马尾", "官方看板娘"},
			PreviewImage:    "file:///android_asset/models/hoshino/preview.png",
			PreviewColorHex: "#FF7B61FF",
			HairColorHex:    "#6B4E71",
			EyeColorHex:     "#5D54A8",
			SkirtColorHex:   "#3B4A6B",
			VoiceName:       "zh-CN-XiaoyiNeural",
			DefaultVoice:    "zh-CN-XiaoyiNeural",
			GreetingText:    "哼哼，现实世界的信号终于连上啦！我就站在你的书桌前哦~",
			DownloadURL:     "https://persona-ar.oss.example.com/models/hoshino.zip",
			FileSize:        "12.4 MB",
			DownloadsCount:  12890,
			Rating:          4.9,
			IsInstalled:     true,
		},
		{
			ID:              "luna",
			Name:            "露娜 (Luna)",
			Version:         "1.1.0",
			Author:          "SilverStar Works",
			Description:     "银白长发、赤色眼瞳的高冷毒舌小魔女。嘴上嫌弃，其实很黏人。",
			Tags:            []string{"白毛", "红瞳", "高冷", "工坊热门"},
			PreviewImage:    "file:///android_asset/models/luna/preview.png",
			PreviewColorHex: "#FF5277",
			HairColorHex:    "#E0E2EB",
			EyeColorHex:     "#FF2E63",
			SkirtColorHex:   "#1E1A29",
			VoiceName:       "zh-CN-XiaoxiaoNeural",
			DefaultVoice:    "zh-CN-XiaoxiaoNeural",
			GreetingText:    "居然把我召唤到了三维现实中…笨蛋使魔，可别指望我会夸你。",
			DownloadURL:     "https://persona-ar.oss.example.com/models/luna.zip",
			FileSize:        "14.1 MB",
			DownloadsCount:  9840,
			Rating:          4.8,
			IsInstalled:     true,
		},
		{
			ID:              "airi",
			Name:            "艾莉 (Airi)",
			Version:         "1.2.0",
			Author:          "WarmHeart Lab",
			Description:     "温柔体贴的邻家治愈学妹。在你累的时候会微笑着静静陪伴你。",
			Tags:            []string{"治愈", "学妹", "温柔", "陪伴"},
			PreviewImage:    "file:///android_asset/models/airi/preview.png",
			PreviewColorHex: "#00E676",
			HairColorHex:    "#8D6E63",
			EyeColorHex:     "#43A047",
			SkirtColorHex:   "#FFF3E0",
			VoiceName:       "zh-CN-YunjianNeural",
			DefaultVoice:    "zh-CN-YunjianNeural",
			GreetingText:    "学长，今天也辛苦啦~ 我已经投射在你的身边了，随时跟我聊聊天吧！",
			DownloadURL:     "https://persona-ar.oss.example.com/models/airi.zip",
			FileSize:        "11.8 MB",
			DownloadsCount:  15300,
			Rating:          5.0,
			IsInstalled:     false,
		},
		{
			ID:              "cyber_k",
			Name:            "K-07 (Cyber K)",
			Version:         "2.0.0",
			Author:          "Neon Future 3D",
			Description:     "从未来赛博空间穿越而来的全息机甲少女，具备 3D 空间立体投射网格。",
			Tags:            []string{"3D模型", "赛博朋克", "机甲", "ARCore原生"},
			PreviewImage:    "file:///android_asset/models/cyber_k/preview.png",
			PreviewColorHex: "#00E5FF",
			HairColorHex:    "#00E5FF",
			EyeColorHex:     "#76FF03",
			SkirtColorHex:   "#212121",
			VoiceName:       "zh-CN-YunyangNeural",
			DefaultVoice:    "zh-CN-YunyangNeural",
			GreetingText:    "全息网络同步完毕。指挥官，现实环境侦测中，已准备就绪。",
			DownloadURL:     "https://persona-ar.oss.example.com/models/cyber_k.zip",
			FileSize:        "18.6 MB",
			DownloadsCount:  8400,
			Rating:          4.7,
			IsInstalled:     false,
		},
	}
	storeMu sync.RWMutex
)

func RegisterStoreRoutes(rg *gin.RouterGroup) {
	rg.GET("/store/models", handleGetStoreModels)
	rg.GET("/store/models/:id", handleGetStoreModelByID)
	rg.POST("/store/models/publish", handlePublishStoreModel)
	rg.GET("/persona/memories", handleGetMemories)
	rg.POST("/persona/memories", handleCreateMemory)
	rg.DELETE("/persona/memories/:id", handleDeleteMemory)
}

func handleGetStoreModels(c *gin.Context) {
	tag := strings.TrimSpace(c.Query("tag"))

	storeMu.RLock()
	defer storeMu.RUnlock()

	if tag == "" {
		c.JSON(http.StatusOK, storeModels)
		return
	}

	results := make([]model.PersonaModelManifest, 0)
	for _, m := range storeModels {
		for _, t := range m.Tags {
			if strings.EqualFold(t, tag) {
				results = append(results, m)
				break
			}
		}
	}
	c.JSON(http.StatusOK, results)
}

func handleGetStoreModelByID(c *gin.Context) {
	id := c.Param("id")

	storeMu.RLock()
	defer storeMu.RUnlock()

	for _, m := range storeModels {
		if m.ID == id {
			c.JSON(http.StatusOK, m)
			return
		}
	}
	c.JSON(http.StatusNotFound, gin.H{"detail": fmt.Sprintf("未在工坊中找到 ID 为 %s 的模型", id)})
}

func handlePublishStoreModel(c *gin.Context) {
	var manifest model.PersonaModelManifest
	if err := c.ShouldBindJSON(&manifest); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	storeMu.Lock()
	defer storeMu.Unlock()

	for idx, m := range storeModels {
		if m.ID == manifest.ID {
			storeModels[idx] = manifest
			c.JSON(http.StatusOK, manifest)
			return
		}
	}

	storeModels = append(storeModels, manifest)
	c.JSON(http.StatusOK, manifest)
}

func handleGetMemories(c *gin.Context) {
	userID := c.DefaultQuery("user_id", "user_default")
	personaID := c.Query("persona_id")

	memories := persona.DefaultMemoryManager.GetMemories(userID, personaID, 20)
	c.JSON(http.StatusOK, memories)
}

func handleCreateMemory(c *gin.Context) {
	var req model.CreateMemoryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"detail": err.Error()})
		return
	}

	userID := req.UserID
	if userID == "" {
		userID = "user_default"
	}
	personaID := req.PersonaID
	if personaID == "" {
		personaID = "hoshino"
	}
	category := req.Category
	if category == "" {
		category = persona.MemoryCategorySharedMoment
	}
	importance := req.Importance
	if importance <= 0 {
		importance = 3
	}

	item := persona.DefaultMemoryManager.AddMemory(userID, personaID, req.Content, category, importance)
	c.JSON(http.StatusOK, item)
}

func handleDeleteMemory(c *gin.Context) {
	id := c.Param("id")
	userID := c.DefaultQuery("user_id", "user_default")

	if persona.DefaultMemoryManager.DeleteMemory(userID, id) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "deleted_id": id})
	} else {
		c.JSON(http.StatusNotFound, gin.H{"detail": fmt.Sprintf("未找到 ID 为 %s 的记忆条目", id)})
	}
}
