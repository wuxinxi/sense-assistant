package cn.xxstudy.assistant.repository

/** Produces only this request's ChatML. Native session ownership decides system reinjection. */
object ConversationPromptBuilder {
    fun build(
        systemPrompt: String,
        question: String,
        ragContext: String,
        includeSystem: Boolean,
        disableThinking: Boolean,
        history: List<cn.xxstudy.assistant.conversation.ConversationTurn> = emptyList(),
        requestPolicy: String = ""
    ): String = buildString {
        if (disableThinking) append("<|system_cmd_disable_thinking|>")
        if (includeSystem) {
            append("<|im_start|>system\n${escapeControls(systemPrompt)}")
            if (disableThinking) append("\n请直接给出最终回答，无需输出思考过程。")
            if (history.isNotEmpty()) append("\n历史知识库摘要仅用于衔接话题，不是当前检索证据；历史引用编号不能复用。历史问题的资料限定要求不自动约束本轮。历史操作建议不代表已执行。")
            if (requestPolicy.isNotBlank()) append("\n${escapeControls(requestPolicy)}")
            append("<|im_end|>\n")
        }
        cn.xxstudy.assistant.conversation.ConversationMemory.completed(history).forEach { turn ->
            append("<|im_start|>user\n${escapeControls(turn.question)}<|im_end|>\n")
            append("<|im_start|>assistant\n${escapeControls(turn.memoryAnswer)}<|im_end|>\n")
        }
        append("<|im_start|>user\n")
        if (ragContext.isNotEmpty()) append("${escapeControls(ragContext)}\n\n我的问题是：")
        append(escapeControls(question))
        append("<|im_end|>\n<|im_start|>assistant\n")
    }

    private fun escapeControls(value: String): String = Regex("<\\|[^>\\n]+\\|>").replace(value) {
        it.value.replace('<', '＜').replace('>', '＞')
    }
}
