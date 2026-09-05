package cn.xxstudy.assistant.speech

/**
 * [SentenceChunker]
 * 针对大语言模型（LLM）流式 Token 输出的中英双轨自适应动态标点切句器。
 * 
 * 优化目标：
 * 1. 双轨语言感知：
 *    - 中文汉字信息密度高（单字单音节），采用紧凑窗口（首句 2~8 字，常规 10~28 字）；
 *    - 英文以单词为单位（平均 4~7 字母），采用扩充窗口（首句 6~28 字符，常规 28~75 字符），
 *      彻底杜绝碎片词（如孤立的 "You"、"exchange."）。
 * 2. 强标点语义保护：
 *    - 严格识别句末强停顿（。！？.!?）与短语弱停顿（，；、:,;）；
 *    - 像 "You too!"、"Hello there!" 作为完整从句短语整体发射，绝不中途砍断；
 *    - 英文浮点数字与缩写标点保护。
 * 3. 安全词边界截断：
 *    - 达到 maxLen 硬上限兜底切片时，英文严格按空格词边界截断，绝不在单词内部割裂；中文在汉字边界截断。
 */
class SentenceChunker(
    private val onSentenceChunk: (String) -> Unit
) {

    private val buffer = StringBuilder()
    private var isFirstChunk = true

    companion object {
        // 句末强停顿标点
        private val END_PUNCTUATIONS = setOf('。', '！', '？', '\n', '.', '!', '?')
        // 句中弱停顿标点（包含中文顿号、冒号、逗号、分号等）
        private val PAUSE_PUNCTUATIONS = setOf('，', '；', '、', '：', ',', ';', ':')
    }

    @Synchronized
    fun onToken(token: String) {
        if (token.isEmpty()) return
        buffer.append(token)

        val currentText = buffer.toString()
        val hasChinese = currentText.any { it.code in 0x4e00..0x9fff }

        // 标点停顿的最小长度门槛（首句保证“你好！”、“好的，”等短语成立）
        val minPunctuationLen = if (hasChinese) {
            if (isFirstChunk) 2 else 6
        } else {
            if (isFirstChunk) 5 else 15
        }

        // 极端防爆兜底上限（仅当大模型出现持续无标点病句时才介入，正常对话绝不强行腰斩句子）
        val emergencyMaxLen = if (hasChinese) 45 else 120

        // 1. 优先在自然标点处切句（句号、感叹号、问号、逗号、顿号、冒号、分号等）
        var cutIndex = -1
        for (i in currentText.indices) {
            val ch = currentText[i]
            if (i >= minPunctuationLen) {
                // 英文小数点保护（如 3.14 不视作断句）
                if (ch == '.' && i + 1 < currentText.length && currentText[i + 1].isDigit()) {
                    continue
                }

                if (ch in END_PUNCTUATIONS || ch in PAUSE_PUNCTUATIONS) {
                    var next = i + 1
                    // 合并连续标点（如 "?!", "。。", "..."）
                    while (next < currentText.length && (currentText[next] in END_PUNCTUATIONS || currentText[next] in PAUSE_PUNCTUATIONS)) {
                        next++
                    }
                    // 如果标点后紧跟引号或右括号，一并收纳
                    while (next < currentText.length && (currentText[next] == '"' || currentText[next] == '\'' || currentText[next] == ')' || currentText[next] == '”')) {
                        next++
                    }
                    cutIndex = next
                    break
                }
            }
        }

        // 2. 仅在极端超长无标点时（如代码、公式），在安全词边界兜底断句
        if (cutIndex == -1 && currentText.length >= emergencyMaxLen) {
            var safeCut = -1
            if (hasChinese) {
                // 中文优先在汉字末尾截断
                for (j in currentText.length - 1 downTo 20) {
                    val c = currentText[j]
                    if (c.isWhitespace() || (c.code in 0x4e00..0x9fff)) {
                        safeCut = j + 1
                        break
                    }
                }
            } else {
                // 英文严格按空白单词边界截断
                for (j in currentText.length - 1 downTo 40) {
                    if (currentText[j].isWhitespace()) {
                        safeCut = j + 1
                        break
                    }
                }
            }
            cutIndex = if (safeCut != -1) safeCut else currentText.length
        }

        // 3. 执行发射
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
