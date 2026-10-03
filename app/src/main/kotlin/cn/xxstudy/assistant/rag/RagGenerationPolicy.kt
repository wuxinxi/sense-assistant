package cn.xxstudy.assistant.rag

/** Keeps the small model's limited generation budget for grounded answer text. */
object RagGenerationPolicy {
    fun shouldDisableThinking(
        userEnabledThinking: Boolean,
        modelSupportsThinking: Boolean,
        hasKnowledgeMatches: Boolean
    ): Boolean = modelSupportsThinking && (!userEnabledThinking || hasKnowledgeMatches)
}
