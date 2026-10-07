package cn.xxstudy.assistant.conversation

import cn.xxstudy.assistant.viewmodel.ChatMessage

internal fun ChatMessage.toDisplaySnapshot() = ConversationDisplaySnapshot(
    sources = knowledgeSources, retrievalInfo = knowledgeRetrievalInfo,
    metrics = metrics, isKnowledgeExcerpt = isKnowledgeExcerpt
)

/** The same projection is used by cold startup and restart regression tests. */
internal fun ConversationTurn.toRestoredChatMessages(): List<ChatMessage> {
    val display = displaySnapshot.takeIf { state == TurnState.COMPLETED }
    return listOf(
        ChatMessage(userMessageId, true, question),
        ChatMessage(answerMessageId, false,
            if (state == TurnState.COMPLETED) answer else "（上轮未完成，未纳入模型记忆）",
            isKnowledgeExcerpt = display?.isKnowledgeExcerpt ?: (kind !in setOf(AnswerKind.GENERAL, AnswerKind.USER_RECAP)),
            isConversationRecap = kind == AnswerKind.USER_RECAP,
            knowledgeSources = display?.sources.orEmpty(),
            knowledgeRetrievalInfo = display?.retrievalInfo,
            isHistoricalKnowledge = !display?.sources.isNullOrEmpty(),
            metrics = display?.metrics ?: when (kind) {
                AnswerKind.USER_RECAP -> "会话回顾 · 仅来自当时的聊天记录"
                AnswerKind.SOURCE_BACKED, AnswerKind.SOURCE_LOOKUP, AnswerKind.MIXED ->
                    "历史知识库回答 · 未保存原文快照，需重新检索"
                else -> null
            })
    )
}
