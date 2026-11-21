package model

type EmotionType string

const (
	EmotionNeutral   EmotionType = "NEUTRAL"
	EmotionJoy       EmotionType = "JOY"
	EmotionShy       EmotionType = "SHY"
	EmotionSad       EmotionType = "SAD"
	EmotionSurprised EmotionType = "SURPRISED"
	EmotionThinking  EmotionType = "THINKING"
	EmotionAngry     EmotionType = "ANGRY"
)

type ActionType string

const (
	ActionIdle      ActionType = "IDLE"
	ActionWave      ActionType = "WAVE"
	ActionBlush     ActionType = "BLUSH"
	ActionNod       ActionType = "NOD"
	ActionShakeHead ActionType = "SHAKE_HEAD"
	ActionPout      ActionType = "POUT"
	ActionBow       ActionType = "BOW"
	ActionJump      ActionType = "JUMP"
)

type TouchPart string

const (
	TouchPartHead  TouchPart = "HEAD"
	TouchPartCheek TouchPart = "CHEEK"
	TouchPartHand  TouchPart = "HAND"
	TouchPartBody  TouchPart = "BODY"
)

type ChatRequest struct {
	Message string `json:"message" binding:"required"`
	UserID  string `json:"user_id,omitempty"`
}

type ChatResponse struct {
	Text            string      `json:"text"`
	Emotion         EmotionType `json:"emotion"`
	Action          ActionType  `json:"action"`
	Expression      string      `json:"expression"`
	AudioBase64     *string     `json:"audio_base64"`
	AudioFormat     string      `json:"audio_format"`
	AffinityChange  int         `json:"affinity_change"`
	CurrentAffinity int         `json:"current_affinity"`
	Mood            string      `json:"mood"`
}

type VoiceInteractRequest struct {
	UserID      string  `json:"user_id,omitempty"`
	AudioBase64 *string `json:"audio_base64,omitempty"`
	AudioFormat string  `json:"audio_format,omitempty"`
	TextPrompt  *string `json:"text_prompt,omitempty"`
}

type TouchRequest struct {
	Part   TouchPart `json:"part" binding:"required"`
	UserID string    `json:"user_id,omitempty"`
}

type TouchResponse struct {
	Text            string      `json:"text"`
	Emotion         EmotionType `json:"emotion"`
	Action          ActionType  `json:"action"`
	Expression      string      `json:"expression"`
	AffinityChange  int         `json:"affinity_change"`
	CurrentAffinity int         `json:"current_affinity"`
	AudioBase64     *string     `json:"audio_base64"`
}

type PersonaStatus struct {
	Name            string   `json:"name"`
	RoleDesc        string   `json:"role_desc"`
	Affinity        int      `json:"affinity"`
	AffinityLevel   string   `json:"affinity_level"`
	Mood            string   `json:"mood"`
	DialogueCount   int      `json:"dialogue_count"`
	RecentMemories  []string `json:"recent_memories"`
}

type PersonaModelManifest struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Version      string   `json:"version"`
	Author       string   `json:"author"`
	Description  string   `json:"description"`
	Tags         []string `json:"tags"`
	PreviewImage string   `json:"preview_image"`
	VoiceName    string   `json:"voice_name"`
	GreetingText string   `json:"greeting_text"`
	DownloadURL  string   `json:"download_url"`
	FileSize     string   `json:"file_size"`
}

type MemoryItem struct {
	ID         string  `json:"id"`
	UserID     string  `json:"user_id"`
	PersonaID  string  `json:"persona_id"`
	Category   string  `json:"category"`
	Content    string  `json:"content"`
	Importance int     `json:"importance"`
	Timestamp  float64 `json:"timestamp"`
}

type SwitchPersonaRequest struct {
	UserID    string `json:"user_id"`
	PersonaID string `json:"persona_id"`
}
