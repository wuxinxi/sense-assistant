package cn.xxstudy.assistant.speech

/**
 * [SentenceChunker]
 * 针对大语言模型（LLM）流式 Token 输出的动态标点切句器。
 * 
 * 优化目标：
 * 1. 极速首字发声：首句只要达到 4~8 字符且遇到停顿标点，立即发射给 TTS 合成。
 * 2. 语调自然连贯：后续句子合并至 12~25 字符，避免切得过碎导致发音生硬。
 */
class SentenceChunker(
    private val onSentenceChunk: (String) -> Unit
) {

    private val buffer = StringBuilder()
    private var isFirstChunk = true

    companion object {
        // 句末强停顿标点
        private val END_PUNCTUATIONS = setOf('。', '！', '？', '\n', '.', '!', '?')
        // 句中弱停顿标点（包含中文顿号、冒号等）
        private val PAUSE_PUNCTUATIONS = setOf('，', '；', '、', '：', ',', ';', ':')
    }

    @Synchronized
    fun onToken(token: String) {
        if (token.isEmpty()) return
        buffer.append(token)

        val currentText = buffer.toString()
        val minLen = if (isFirstChunk) 2 else 8
        val maxLen = if (isFirstChunk) 8 else 20

        // 检查是否出现标点
        var cutIndex = -1
        for (i in currentText.indices) {
            val ch = currentText[i]
            if (i >= minLen) {
                if (ch in END_PUNCTUATIONS || ch in PAUSE_PUNCTUATIONS) {
                    cutIndex = i + 1
                    break
                }
            }
        }

        // 抢跑与兜底：达到 maxLen 时若无标点，强制在当前字处切片，绝不堆积
        if (cutIndex == -1 && currentText.length >= maxLen) {
            cutIndex = currentText.length
        }

        if (cutIndex > 0) {
            val chunk = currentText.substring(0, cutIndex).trim()
            buffer.delete(0, cutIndex)
            if (chunk.isNotEmpty()) {
                isFirstChunk = false
                onSentenceChunk(chunk)
            }
        }
    }

    @Synchronized
    fun flush() {
        val remaining = buffer.toString().trim()
        buffer.clear()
        isFirstChunk = true
        if (remaining.isNotEmpty()) {
            onSentenceChunk(remaining)
        }
    }

    @Synchronized
    fun reset() {
        buffer.clear()
        isFirstChunk = true
    }
}
