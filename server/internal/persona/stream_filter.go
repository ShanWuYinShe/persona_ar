package persona

import (
	"regexp"
	"strings"
)

var reKnownMetadataTag = regexp.MustCompile(`^<\s*(?:emotion|action|expr)\s*:[^<>]*>$`)

// StreamTagFilter 增量过滤流式输出中的 <emotion:...> / <action:...> / <expr:...> 元数据标签，
// 避免原始控制标签在前端打字机气泡中闪现，同时保留正常的 '<' 字符（如 "<3" 或 "a < b"）。
type StreamTagFilter struct {
	buf       strings.Builder
	inBracket bool
}

// Feed 输入一段流式增量文本，返回当前可安全下发给客户端的干净文本。
func (f *StreamTagFilter) Feed(chunk string) string {
	if chunk == "" {
		return ""
	}
	var out strings.Builder
	for _, r := range chunk {
		if !f.inBracket {
			if r == '<' {
				f.inBracket = true
				f.buf.Reset()
				f.buf.WriteRune(r)
			} else {
				out.WriteRune(r)
			}
			continue
		}

		f.buf.WriteRune(r)
		if r == '<' {
			// 先前的 '<' 并非标签（如 "<3 <emotion:JOY>"），先把前面的字符原样吐出，再从当前 '<' 重新缓冲
			prev := f.buf.String()
			out.WriteString(prev[:len(prev)-1])
			f.buf.Reset()
			f.buf.WriteRune('<')
			continue
		}
		if r == '>' {
			token := f.buf.String()
			f.buf.Reset()
			f.inBracket = false
			if !reKnownMetadataTag.MatchString(token) {
				out.WriteString(token)
			}
			continue
		}

		// 非法或过长的 '<...' 不可能为元数据标签（遇到换行或超过 48 rune 时立即原样释放）
		if r == '\n' || f.buf.Len() > 48 {
			out.WriteString(f.buf.String())
			f.buf.Reset()
			f.inBracket = false
		}
	}
	return out.String()
}

// Flush 在流结束时释放尚未闭合的缓冲字符（若是不完整的 "<emotion:..." 前缀则丢弃）。
func (f *StreamTagFilter) Flush() string {
	if !f.inBracket || f.buf.Len() == 0 {
		f.inBracket = false
		f.buf.Reset()
		return ""
	}
	rem := f.buf.String()
	f.buf.Reset()
	f.inBracket = false
	lower := strings.ToLower(rem)
	if strings.HasPrefix(lower, "<emotion") || strings.HasPrefix(lower, "<action") || strings.HasPrefix(lower, "<expr") || rem == "<" {
		return ""
	}
	return rem
}
