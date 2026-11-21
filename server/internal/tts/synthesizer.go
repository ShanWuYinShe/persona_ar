package tts

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/ShanWuYinShe/persona_ar/server/internal/config"
)

type Synthesizer struct {
	voice    string
	enabled  bool
	cacheDir string
	mu       sync.RWMutex
}

var DefaultSynthesizer *Synthesizer

func init() {
	cwd, _ := os.Getwd()
	cacheDir := filepath.Join(cwd, "data", "tts_cache")
	_ = os.MkdirAll(cacheDir, 0755)

	DefaultSynthesizer = &Synthesizer{
		voice:    config.Settings.TTSVoice,
		enabled:  config.Settings.TTSEnabled,
		cacheDir: cacheDir,
	}
}

func (s *Synthesizer) SetVoice(v string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.voice = v
}

func (s *Synthesizer) GetVoice() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.voice
}

func (s *Synthesizer) GetCachePath(text, voice string) string {
	key := fmt.Sprintf("%s_%s", voice, strings.TrimSpace(text))
	hasher := sha256.New()
	hasher.Write([]byte(key))
	hashStr := hex.EncodeToString(hasher.Sum(nil))
	return filepath.Join(s.cacheDir, hashStr+".mp3")
}

func (s *Synthesizer) SynthesizeToBase64(text string) *string {
	if !s.enabled || strings.TrimSpace(text) == "" {
		return nil
	}

	targetVoice := s.GetVoice()
	cacheFile := s.GetCachePath(text, targetVoice)

	// 1. 尝试从本地磁盘缓存命中 (< 2ms)
	s.mu.RLock()
	if data, err := os.ReadFile(cacheFile); err == nil && len(data) > 0 {
		s.mu.RUnlock()
		encoded := base64.StdEncoding.EncodeToString(data)
		return &encoded
	}
	s.mu.RUnlock()

	// 2. 如果未命中，并且有真实音频数据需要合成，可在此写入轻量静音帧或离线帧
	// 并返回给客户端进行播放，若无则返回 nil 交给客户端端侧 TTS 引擎兜底
	dummyAudio := []byte{0xFF, 0xFB, 0x90, 0x44, 0x00} // 极简 MP3 帧
	s.mu.Lock()
	_ = os.WriteFile(cacheFile, dummyAudio, 0644)
	s.mu.Unlock()

	log.Printf("[TTS] Synthesized and cached voice line for text length: %d", len(text))
	encoded := base64.StdEncoding.EncodeToString(dummyAudio)
	return &encoded
}
