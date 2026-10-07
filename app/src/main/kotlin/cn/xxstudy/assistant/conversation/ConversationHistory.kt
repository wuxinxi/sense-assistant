package cn.xxstudy.assistant.conversation

enum class AnswerKind { GENERAL, SOURCE_BACKED, SOURCE_LOOKUP, FALLBACK, STATUS, ACTION_PROPOSAL, MIXED, CLARIFICATION, USER_RECAP }
enum class TurnState { PENDING, COMPLETED, INTERRUPTED, FAILED }

data class ConversationTurn(
    val requestId: String,
    val userMessageId: Int,
    val answerMessageId: Int,
    val question: String,
    val answer: String,
    val memoryAnswer: String,
    val kind: AnswerKind,
    val state: TurnState,
    val retrievalTopic: String = "",
    val sourceRequired: Boolean = false,
    val displaySnapshot: ConversationDisplaySnapshot? = null
)

data class TurnTicket(val requestId: String, val epoch: Long)

/** Whole finalized exchanges only: never replay a draft or chain of thought. */
object ConversationMemory {
    fun completed(turns: List<ConversationTurn>): List<ConversationTurn> =
        turns.filter { it.state == TurnState.COMPLETED && it.memoryAnswer.isNotBlank() }

    fun project(answer: String, kind: AnswerKind): String = when (kind) {
        AnswerKind.GENERAL -> answer
        // The old citation numbering belongs to that request, not the new evidence.
        AnswerKind.SOURCE_BACKED, AnswerKind.MIXED ->
            (if (kind == AnswerKind.MIXED) "历史混合回答（资料说明与通用补充均不是本轮证据）：\n"
            else "历史知识库摘要（仅用于衔接对话，不是本轮证据）：\n") +
            answer.replace(Regex("\\[(\\d+)](?:\\([^)]*\\))?"), "")
                .replace(Regex("\\[([^]]+)]\\(obsidian://[^)]*\\)"), "$1")
        AnswerKind.SOURCE_LOOKUP -> "已查阅该问题的知识库原文；原文未进入会话记忆，需要内容时重新检索。"
        AnswerKind.FALLBACK -> "上轮未得到可靠答案，需要重新检索或澄清；未将草稿作为事实。"
        AnswerKind.STATUS -> "已查看知识库状态；状态可能变化，需要时重新查询。"
        AnswerKind.ACTION_PROPOSAL -> "已提供待确认操作建议；不能据此判断操作已执行。"
        AnswerKind.CLARIFICATION -> "上轮需要澄清主题，尚未检索或确认任何资料事实。"
        // Do not recursively replay the quoted questions as new facts or current instructions.
        AnswerKind.USER_RECAP -> "已按本地聊天记录回顾用户提出的问题；没有查询知识库，也没有新增资料事实。"
    }
}
