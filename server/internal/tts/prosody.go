package tts

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/ShanWuYinShe/persona_ar/server/internal/model"
)

// Prosody Edge-TTS 的 SSML 韵律参数。
type Prosody struct {
	Rate   string // 语速，形如 "+6%"
	Pitch  string // 音高，形如 "+8Hz"
	Volume string // 音量，形如 "+2%"
}

// emotionProsody 情绪 → 韵律映射表。
//
// 设计依据：Edge-TTS 的 prosody 通过 SSML 的 rate/pitch/volume 控制，数值为相对原声的偏移；
// 这里按中文情感语音的常见感知规律调参（参考 SSML 韵律惯例与情感 TTS 的普遍做法）：
//   - JOY 喜悦：语速加快、音调上扬、音量略升，表现兴奋与元气；
//   - SHY 害羞：语速略慢、音高略升（说话发虚）、音量收敛，避免过于外放；
//   - SAD 难过：语速明显放慢、音调下压、音量降低，表现低落无力；
//   - SURPRISED 惊讶：短促加快、音调明显上扬、音量提高；
//   - THINKING 思考：语速放慢、音调略降、音量略低，表现沉吟；
//   - ANGRY 生气：语速加快、音量提高，音调略升以示激动；
//   - SLEEPY 慵懒/困倦：语速大幅放慢、音调压低、音量降低，气息拖沓；
//   - CONFIDENT 自信：语速略快、音调略降、音量提高，表现沉稳笃定；
//   - NEUTRAL 中性：保持原声。
//
// 所有偏移最终都会 clamp 到 rate ±30% / pitch ±40Hz / volume ±30%，避免极端参数导致合成异常。
var emotionProsody = map[model.EmotionType]Prosody{
	model.EmotionNeutral:   {Rate: "+0%", Pitch: "+0Hz", Volume: "+0%"},
	model.EmotionJoy:       {Rate: "+6%", Pitch: "+8Hz", Volume: "+2%"},
	model.EmotionShy:       {Rate: "-4%", Pitch: "+4Hz", Volume: "-6%"},
	model.EmotionSad:       {Rate: "-12%", Pitch: "-8Hz", Volume: "-8%"},
	model.EmotionSurprised: {Rate: "+8%", Pitch: "+12Hz", Volume: "+6%"},
	model.EmotionThinking:  {Rate: "-6%", Pitch: "-2Hz", Volume: "-2%"},
	model.EmotionAngry:     {Rate: "+10%", Pitch: "+2Hz", Volume: "+8%"},
	model.EmotionSleepy:    {Rate: "-18%", Pitch: "-10Hz", Volume: "-10%"},
	model.EmotionConfident: {Rate: "+2%", Pitch: "-2Hz", Volume: "+4%"},
}

// ProsodyFor 计算最终韵律：情绪基础表 + 好感度微调。
func ProsodyFor(emotion model.EmotionType, affinity int) Prosody {
	base, ok := emotionProsody[emotion]
	if !ok {
		base = emotionProsody[model.EmotionNeutral]
	}
	bonus := affinityProsody(affinity)
	return Prosody{
		Rate:   formatPercent(clampInt(parsePercent(base.Rate)+parsePercent(bonus.Rate), -30, 30)),
		Pitch:  formatPitch(clampInt(parsePitchHz(base.Pitch)+parsePitchHz(bonus.Pitch), -40, 40)),
		Volume: formatPercent(clampInt(parsePercent(base.Volume)+parsePercent(bonus.Volume), -30, 30)),
	}
}

// affinityProsody 好感度对韵律的细微加成：越熟悉，语速越快一点、音调略高一点，
// 表现「更亲近、更愿意说话」；按 30 / 70 / 100 分 4 档（与人设好感等级一致），
// 不做逐点变化，避免缓存 key 因好感度连续变化而碎片化。
func affinityProsody(affinity int) Prosody {
	level := 0
	switch {
	case affinity >= 100:
		level = 3
	case affinity >= 70:
		level = 2
	case affinity >= 30:
		level = 1
	}
	return Prosody{
		Rate:   formatPercent(level),
		Pitch:  formatPitch(level),
		Volume: "+0%",
	}
}

// parsePercent 解析 "+6%" / "-12%" 这类百分比偏移，异常值按 0 处理。
func parsePercent(value string) int {
	return parseIntWithUnit(value, "%")
}

// parsePitchHz 解析 "+8Hz" / "-10Hz" 这类音高偏移，异常值按 0 处理。
func parsePitchHz(value string) int {
	return parseIntWithUnit(value, "Hz")
}

func parseIntWithUnit(value, unit string) int {
	trimmed := strings.TrimSpace(value)
	trimmed = strings.TrimSuffix(trimmed, unit)
	parsed, err := strconv.Atoi(trimmed)
	if err != nil {
		return 0
	}
	return parsed
}

// formatPercent 输出显式带符号的百分比，例如 0 -> "+0%"，6 -> "+6%"，-12 -> "-12%"。
func formatPercent(value int) string {
	return fmt.Sprintf("%+d%%", value)
}

// formatPitch 输出显式带符号的音高偏移，例如 0 -> "+0Hz"，8 -> "+8Hz"。
func formatPitch(value int) string {
	return fmt.Sprintf("%+dHz", value)
}

func clampInt(value, minValue, maxValue int) int {
	if value < minValue {
		return minValue
	}
	if value > maxValue {
		return maxValue
	}
	return value
}
