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
	EmotionSleepy    EmotionType = "SLEEPY"
	EmotionConfident EmotionType = "CONFIDENT"
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
	ActionYawn      ActionType = "YAWN"
	ActionSleep     ActionType = "SLEEP"
	ActionSmug      ActionType = "SMUG"
	ActionSit       ActionType = "SIT"
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

type VisionInteractRequest struct {
	UserID      string `json:"user_id,omitempty"`
	Message     string `json:"message,omitempty"`
	ImageBase64 string `json:"image_base64" binding:"required"`
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
	PersonaID      string   `json:"persona_id,omitempty"`
	Name           string   `json:"name"`
	RoleDesc       string   `json:"role_desc"`
	Affinity       int      `json:"affinity"`
	AffinityLevel  string   `json:"affinity_level"`
	Mood           string   `json:"mood"`
	DialogueCount  int      `json:"dialogue_count"`
	RecentMemories []string `json:"recent_memories"`
}

type PersonaModelManifest struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Version         string   `json:"version"`
	Author          string   `json:"author"`
	Description     string   `json:"description"`
	Tags            []string `json:"tags"`
	PreviewImage    string   `json:"preview_image,omitempty"`
	PreviewColorHex string   `json:"preview_color,omitempty"`
	HairColorHex    string   `json:"hair_color,omitempty"`
	EyeColorHex     string   `json:"eye_color,omitempty"`
	SkirtColorHex   string   `json:"skirt_color,omitempty"`
	VoiceName       string   `json:"voice_name"`
	DefaultVoice    string   `json:"default_voice,omitempty"`
	GreetingText    string   `json:"greeting_text"`
	DownloadURL     string   `json:"download_url,omitempty"`
	FileSize        string   `json:"file_size,omitempty"`
	DownloadsCount  int      `json:"downloads_count,omitempty"`
	Rating          float32  `json:"rating,omitempty"`
	IsInstalled     bool     `json:"is_installed"`
}

type CreateMemoryRequest struct {
	UserID     string `json:"user_id"`
	PersonaID  string `json:"persona_id"`
	Category   string `json:"category"`
	Content    string `json:"content" binding:"required"`
	Importance int    `json:"importance"`
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
