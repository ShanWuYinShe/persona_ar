package tts

import (
	"regexp"
	"strings"
	"unicode"
)

var (
	// reTTSXMLTag 兜底剥离 <emotion:JOY> / <action:WAVE> / <expr:SMILE> 之类的标签。
	// 引擎解析大模型回复时已剥离过一次，这里防止其它调用路径把标签带进合成。
	reTTSXMLTag = regexp.MustCompile(`<[^<>]{0,80}>`)

	// reTTSStageDirection 圆括号/方括号中的舞台说明，例如「（对讲机传来指挥官温和的呼吸与声音）」「(小声)」。
	// 只删除命中动作/旁白关键词的括号段，避免误删真正有语义的括号内容（如「（真的吗）」）。
	reTTSStageDirection = regexp.MustCompile(`[(（\[【][^(（)）\[\]【】]{0,40}(?:对讲机|传来|笑着说|叹气|小声|语气|动作|旁白|背景音|音乐|停顿|轻笑|咳嗽|思考|内心|系统提示|机械音|音效)[^(（)）\[\]【】]{0,40}[)）\]】]`)

	// reTTSSymbolParen 纯符号/颜文字括号段，例如 (｡･ω･｡)、(^_^)、(・ω・)。
	reTTSSymbolParen = regexp.MustCompile(`[(（\[【][^\p{L}\p{N}]{1,12}[)）\]】]`)

	// reTTSParenGroup 任意括号段，用于按内容判断是否为无意义颜文字括号（如 (｡･ω･｡)）。
	reTTSParenGroup = regexp.MustCompile(`[(（\[【][^()（）\[\]【】]*[)）\]】]`)

	// reTTSEmptyParen 符号被剥离后残留的空括号。
	reTTSEmptyParen = regexp.MustCompile(`[(（\[【]\s*[)）\]】]`)

	// reTTSEllipsis 省略号归一：连续的中文省略号/英文句点转成一个「…」，作为自然停顿。
	reTTSEllipsis = regexp.MustCompile(`…+|．{3,}|\.{3,}`)

	// reTTSTilde 波浪号转逗号：朗读时表现为自然停顿，而不是念出符号。
	reTTSTilde = regexp.MustCompile(`[~～]+`)

	// reTTSSpace 压缩空白。
	reTTSSpace = regexp.MustCompile(`\s+`)

	// reTTSSpaceAroundPunct 去掉紧贴中文标点的空格，避免念出突兀停顿。
	reTTSSpaceAroundPunct = regexp.MustCompile(`\s+([，。！？、；：…])|([，。！？、；：…])\s+`)

	// reTTSMarkdown 不朗读的装饰符（加粗星号、标题井号、反引号）。
	reTTSMarkdown = regexp.MustCompile("[*#\x60]")
)

// CleanText 生成「只用于 TTS 合成」的干净文本，绝不修改返回给客户端的显示文本。
//
// 处理顺序：
//  1. 剥离情绪/动作标签；
//  2. 去掉括号舞台说明与颜文字；
//  3. 去除 emoji / 符号 / 零宽字符 / 控制字符，仅保留中文、英文、数字、标点；
//  4. 省略号与波浪号归一为自然停顿标点；
//  5. 压缩空白、清理空括号；
//  6. 超长文本在句末标点处截断，保护合成时延。
//
// 清洗后若无任何可朗读字符（纯标点/纯 emoji）返回空串，调用方据此跳过合成。
func CleanText(raw string) string {
	if strings.TrimSpace(raw) == "" {
		return ""
	}
	cleaned := strings.TrimSpace(raw)

	cleaned = reTTSXMLTag.ReplaceAllString(cleaned, "")
	cleaned = reTTSStageDirection.ReplaceAllString(cleaned, "")
	cleaned = reTTSSymbolParen.ReplaceAllString(cleaned, "")
	cleaned = reTTSMarkdown.ReplaceAllString(cleaned, "")

	// 波浪号/省略号必须在逐字符过滤前转换：'~' 与 '～' 的 Unicode 类别是 Sm(Symbol)，
	// 先转换才能保留成自然停顿标点，否则会被下面的符号过滤器直接删掉。
	cleaned = reTTSTilde.ReplaceAllString(cleaned, "，")
	cleaned = reTTSEllipsis.ReplaceAllString(cleaned, "…")

	cleaned = filterTTSRunes(cleaned)
	cleaned = removeNonSemanticParens(cleaned)
	cleaned = reTTSEmptyParen.ReplaceAllString(cleaned, "")
	cleaned = reTTSSpaceAroundPunct.ReplaceAllString(cleaned, "$1$2")
	cleaned = reTTSSpace.ReplaceAllString(cleaned, " ")
	cleaned = strings.TrimSpace(cleaned)

	// 去掉头尾多余标点（标点开头会生成怪异的静音/语气）
	cleaned = strings.Trim(cleaned, "，、；：…")
	cleaned = strings.TrimSpace(cleaned)

	if !hasSpeakableRune(cleaned) {
		return ""
	}
	return truncateForTTS(cleaned, maxSynthRunes)
}

// removeNonSemanticParens 删除「无中文/无 ASCII 字母数字」的括号段，例如混合了希腊字母的颜文字 (｡･ω･｡)；
// 保留有语义的括号内容（如「（真的）」「(hello)」）。
func removeNonSemanticParens(input string) string {
	return reTTSParenGroup.ReplaceAllStringFunc(input, func(group string) string {
		runes := []rune(group)
		if len(runes) < 2 {
			return group
		}
		if containsSpeakableSemantic(string(runes[1 : len(runes)-1])) {
			return group
		}
		return ""
	})
}

// containsSpeakableSemantic 判断文本是否包含值得保留的语义字符（中日韩汉字或 ASCII 字母数字）。
func containsSpeakableSemantic(text string) bool {
	for _, r := range text {
		if unicode.Is(unicode.Han, r) {
			return true
		}
		if r < 128 && (unicode.IsLetter(r) || unicode.IsDigit(r)) {
			return true
		}
	}
	return false
}

// filterTTSRunes 逐字符过滤：保留中文/英文/数字/标点/空白，丢弃 emoji 与杂符号。
func filterTTSRunes(input string) string {
	var builder strings.Builder
	builder.Grow(len(input))
	for _, r := range input {
		switch {
		case r == '\n' || r == '\r' || r == '\t':
			builder.WriteRune(' ')
		case unicode.Is(unicode.Co, r), unicode.Is(unicode.Cs, r), unicode.Is(unicode.Cf, r):
			// 私有区字符、代理区字符、零宽连接符/变体选择标记（emoji ZWJ 序列会残留）
			continue
		case unicode.IsSymbol(r):
			// 表情、颜文字符号、♪★✨ 等 Symbol 类字符
			continue
		case unicode.IsMark(r):
			// 组合附加符号（emoji 变体选择符 FE0F 等）
			continue
		case unicode.IsControl(r):
			continue
		case unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.IsPunct(r) || unicode.IsSpace(r):
			builder.WriteRune(r)
		}
	}
	return builder.String()
}

// hasSpeakableRune 判断清洗结果中是否存在可朗读的字母/数字（纯标点不送合成）。
func hasSpeakableRune(text string) bool {
	for _, r := range text {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return true
		}
	}
	return false
}

// truncateForTTS 超长文本截断：尽量切在句末标点，避免合成请求过大、响应过慢。
func truncateForTTS(text string, maxRunes int) string {
	runes := []rune(text)
	if len(runes) <= maxRunes {
		return text
	}
	cut := maxRunes
	// 在截断点前 80 字内找最近的句末标点，找不到就硬截断并补句号
	for i := maxRunes - 1; i >= maxRunes-80 && i > 0; i-- {
		if strings.ContainsRune("。！？…；", runes[i]) {
			cut = i + 1
			break
		}
	}
	truncated := strings.TrimSpace(string(runes[:cut]))
	if truncated == "" {
		return ""
	}
	if !strings.ContainsAny(truncated, "。！？…") {
		truncated += "。"
	}
	return truncated
}
