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
			ID:           "hoshino",
			Name:         "星野 (Hoshino)",
			Version:      "1.0.0",
			Author:       "PersonaAR 官方",
			Description:  "元气傲娇少女。心软口硬，对现实世界充满好奇，时刻陪伴在你的书桌前。",
			Tags:         []string{"元气", "傲娇", "学生", "官方推荐"},
			PreviewImage: "file:///android_asset/models/hoshino/preview.png",
			VoiceName:    "zh-CN-XiaoyiNeural",
			GreetingText: "哼哼，现实世界的信号终于连上啦！我就站在你的书桌前哦~",
			DownloadURL:  "https://persona-ar.oss.example.com/models/hoshino.zip",
			FileSize:     "12.4 MB",
		},
		{
			ID:           "luna",
			Name:         "露娜 (Luna)",
			Version:      "1.0.0",
			Author:       "PersonaAR 官方",
			Description:  "高冷毒舌小魔女。银白长发，赤色眼瞳。嘴上嫌弃，其实格外黏人。",
			Tags:         []string{"魔女", "毒舌", "白发红瞳", "反差萌"},
			PreviewImage: "file:///android_asset/models/luna/preview.png",
			VoiceName:    "zh-CN-XiaoxiaoNeural",
			GreetingText: "居然把我召唤到了三维现实中…笨蛋使魔，可别指望我会夸你。",
			DownloadURL:  "https://persona-ar.oss.example.com/models/luna.zip",
			FileSize:     "14.1 MB",
		},
		{
			ID:           "airi",
			Name:         "艾莉 (Airi)",
			Version:      "1.0.0",
			Author:       "PersonaAR 官方",
			Description:  "温柔治愈学妹。在你疲惫工作时会微笑着静静陪伴，为你元气应援。",
			Tags:         []string{"学妹", "治愈", "温柔", "应援"},
			PreviewImage: "file:///android_asset/models/airi/preview.png",
			VoiceName:    "zh-CN-YunjianNeural",
			GreetingText: "学长，今天也辛苦啦~ 我已经投射在你的身边了，随时跟我聊聊天吧！",
			DownloadURL:  "https://persona-ar.oss.example.com/models/airi.zip",
			FileSize:     "11.8 MB",
		},
		{
			ID:           "cyber_k",
			Name:         "K-07 (Cyber K)",
			Version:      "1.0.0",
			Author:       "工坊创作者 · 零度科技",
			Description:  "赛博未来战术全息少女。装备 3D 网格光效，以干练战术视角观测现实世界。",
			Tags:         []string{"赛博朋克", "机娘", "全息", "战术"},
			PreviewImage: "file:///android_asset/models/cyber_k/preview.png",
			VoiceName:    "zh-CN-YunyangNeural",
			GreetingText: "全息网络同步完毕。指挥官，现实环境侦测中，已准备就绪。",
			DownloadURL:  "https://persona-ar.oss.example.com/models/cyber_k.zip",
			FileSize:     "18.6 MB",
		},
	}
	storeMu sync.RWMutex
)

func RegisterStoreRoutes(rg *gin.RouterGroup) {
	rg.GET("/store/models", handleGetStoreModels)
	rg.GET("/store/models/:id", handleGetStoreModelByID)
	rg.POST("/store/models/publish", handlePublishStoreModel)
	rg.GET("/persona/memories", handleGetMemories)
}

func handleGetStoreModels(c *gin.Context) {
	tag := strings.TrimSpace(c.Query("tag"))

	storeMu.RLock()
	defer storeMu.RUnlock()

	if tag == "" {
		c.JSON(http.StatusOK, storeModels)
		return
	}

	var results []model.PersonaModelManifest
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
