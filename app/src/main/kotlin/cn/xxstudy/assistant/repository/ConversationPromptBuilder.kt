package cn.xxstudy.assistant.repository

/** Produces only this request's ChatML. Native session ownership decides system reinjection. */
object ConversationPromptBuilder {
    fun build(
        systemPrompt: String,
        question: String,
        ragContext: String,
        includeSystem: Boolean,
        disableThinking: Boolean
    ): String = buildString {
        if (disableThinking) append("<|system_cmd_disable_thinking|>")
        if (includeSystem) {
            append("<|im_start|>system\n$systemPrompt")
            if (disableThinking) append("\n请直接给出最终回答，无需输出思考过程。")
            append("<|im_end|>\n")
        }
        append("<|im_start|>user\n")
        if (ragContext.isNotEmpty()) append("$ragContext\n\n我的问题是：")
        append(question)
        append("<|im_end|>\n<|im_start|>assistant\n")
    }
}
