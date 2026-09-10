package cn.xxstudy.assistant.engine

/**
 * [ThinkingStreamParser]
 * 端侧流式思考解析器。
 * 实时监测并分离底层 Token 流中的「思维链 (Thinking Process)」与「正式回答 (Answer)」。
 *
 * 支持模式：
 * 1. MiniCPM5 官方标准格式：`<|thought_begin|>` ... `<|thought_end|>`
 * 2. 通用开源思考格式：`<think>` ... `</think>`
 * 3. 传统无思考格式（直接输出回答）
 */
class ThinkingStreamParser {

    companion object {
        private val START_TAGS = listOf("<|thought_begin|>", "<think>")
        private val END_TAGS = listOf("<|thought_end|>", "</think>")
        private const val MAX_PREFIX_BUFFER_LEN = 32
    }

    data class Snapshot(
        val thinkingText: String?,
        val isThinkingActive: Boolean,
        val answerText: String
    )

    private val thinkingBuffer = StringBuilder()
    private val answerBuffer = StringBuilder()
    private val pendingBuffer = StringBuilder()

    private var hasStartedThinking = false
    private var hasFinishedThinking = false
    private var isThinkingActive = false

    /**
     * 仅当正式回答 (Answer) 内容流出时触发的回调
     * 核心作用：彻底隔绝思考链（Thinking），专门供语音合成（TTS）或文本切句器消费
     */
    var onAnswerChunk: ((String) -> Unit)? = null

    /**
     * 传入新的 Token 片段并驱动状态机更新
     */
    @Synchronized
    fun feed(token: String) {
        if (token.isEmpty()) return

        if (!hasStartedThinking) {
            pendingBuffer.append(token)
            val pendingStr = pendingBuffer.toString()

            // 1. 检查是否匹配任何开始标记
            var matchedStartTag: String? = null
            var matchIndex = -1
            for (tag in START_TAGS) {
                val idx = pendingStr.indexOf(tag)
                if (idx != -1 && (matchIndex == -1 || idx < matchIndex)) {
                    matchIndex = idx
                    matchedStartTag = tag
                }
            }

            if (matchedStartTag != null) {
                // 成功捕获思考起始标签
                hasStartedThinking = true
                isThinkingActive = true
                val afterTag = pendingStr.substring(matchIndex + matchedStartTag.length)
                pendingBuffer.clear()
                if (afterTag.isNotEmpty()) {
                    feed(afterTag)
                }
                return
            }

            // 2. 如果尚未匹配，检查当前缓冲是否可能是开始标签的前缀（如 "<", "<|", "<|th"）
            val isPotentialPrefix = START_TAGS.any { tag ->
                tag.startsWith(pendingStr.trimStart()) || pendingStr.trimStart().isEmpty()
            }

            if (!isPotentialPrefix || pendingStr.length > MAX_PREFIX_BUFFER_LEN) {
                // 确认不是思考标记，直接判定为无思考模型的普通文本回答
                hasStartedThinking = true
                hasFinishedThinking = true
                isThinkingActive = false
                answerBuffer.append(pendingStr)
                onAnswerChunk?.invoke(pendingStr)
                pendingBuffer.clear()
            }
            return
        }

        if (isThinkingActive && !hasFinishedThinking) {
            pendingBuffer.append(token)
            val pendingStr = pendingBuffer.toString()

            // 检查是否出现结束标记
            var matchedEndTag: String? = null
            var endMatchIndex = -1
            for (tag in END_TAGS) {
                val idx = pendingStr.indexOf(tag)
                if (idx != -1 && (endMatchIndex == -1 || idx < endMatchIndex)) {
                    endMatchIndex = idx
                    matchedEndTag = tag
                }
            }

            if (matchedEndTag != null) {
                // 思考结束
                val thinkPart = pendingStr.substring(0, endMatchIndex)
                val afterTag = pendingStr.substring(endMatchIndex + matchedEndTag.length)

                thinkingBuffer.append(thinkPart)
                isThinkingActive = false
                hasFinishedThinking = true
                pendingBuffer.clear()

                // 清理思考结束后的首个多余换行符
                val cleanAnswer = afterTag.trimStart('\r', '\n')
                if (cleanAnswer.isNotEmpty()) {
                    answerBuffer.append(cleanAnswer)
                    onAnswerChunk?.invoke(cleanAnswer)
                }
                return
            }

            // 检查 tail 是否可能属于某个结束标签的前缀，防止跨 token 拆分丢失（例如 "<|" 留在 pending）
            var safeLen = pendingStr.length
            for (tag in END_TAGS) {
                for (prefixLen in tag.length - 1 downTo 1) {
                    val candidate = tag.substring(0, prefixLen)
                    if (pendingStr.endsWith(candidate)) {
                        safeLen = minOf(safeLen, pendingStr.length - prefixLen)
                        break
                    }
                }
            }

            if (safeLen > 0) {
                thinkingBuffer.append(pendingStr.substring(0, safeLen))
                val remaining = pendingStr.substring(safeLen)
                pendingBuffer.clear()
                pendingBuffer.append(remaining)
            }
            return
        }

        // 思考已完成，所有后续字符直接流入正式回答
        // 关键体验优化：在回答正文首个有效字符输出前，彻底过滤掉模型随附的多余换行符（如 \n\n）
        val effectiveToken = if (answerBuffer.isEmpty()) {
            token.trimStart('\r', '\n')
        } else {
            token
        }
        if (effectiveToken.isNotEmpty()) {
            answerBuffer.append(effectiveToken)
            onAnswerChunk?.invoke(effectiveToken)
        }
    }

    /**
     * 推理彻底结束时调用，清空并整理所有剩余缓存
     */
    @Synchronized
    fun finish() {
        if (pendingBuffer.isNotEmpty()) {
            val leftover = pendingBuffer.toString()
            pendingBuffer.clear()
            if (isThinkingActive) {
                thinkingBuffer.append(leftover)
            } else {
                val effective = if (answerBuffer.isEmpty()) leftover.trimStart('\r', '\n') else leftover
                if (effective.isNotEmpty()) {
                    answerBuffer.append(effective)
                    onAnswerChunk?.invoke(effective)
                }
            }
        }
        isThinkingActive = false
        if (hasStartedThinking && !hasFinishedThinking) {
            hasFinishedThinking = true
        }
    }

    /**
     * 获取当前的瞬时快照，用于高频刷新 UI
     */
    @Synchronized
    fun getSnapshot(): Snapshot {
        val thinking = if (thinkingBuffer.isNotEmpty() || isThinkingActive) {
            thinkingBuffer.toString()
        } else {
            null
        }
        return Snapshot(
            thinkingText = thinking,
            isThinkingActive = isThinkingActive,
            answerText = answerBuffer.toString()
        )
    }
}
