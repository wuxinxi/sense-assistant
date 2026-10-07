package cn.xxstudy.assistant.conversation

data class ContextPlan(val prompt: String, val inputTokens: Int, val droppedTurns: Int, val droppedEvidence: Int)
class ContextBudgetExceeded : IllegalArgumentException("系统指令和当前问题超过上下文预算，请缩短问题或提高上下文窗口。")

/** Tokenizer includes the exact native framing, BOS and disabled-thinking prefill. */
object ContextPlanner {
    fun plan(
        turns: List<ConversationTurn>, evidenceCount: Int, contextCapacity: Int,
        countTokens: (String) -> Int,
        buildPrompt: (List<ConversationTurn>, Int) -> String,
        outputReserve: Int = 512, safetyMargin: Int = 32
    ): ContextPlan {
        require(outputReserve > 0 && safetyMargin > 0 && evidenceCount >= 0)
        val inputBudget = contextCapacity - outputReserve - safetyMargin
        val completed = ConversationMemory.completed(turns)
        var history = completed.takeLast(8)
        var evidence = evidenceCount
        // Protect the newest exchange. Drop older whole turns, then low-ranked evidence;
        // never truncate the system instruction or the current question.
        while (true) {
            val prompt = buildPrompt(history, evidence)
            val tokens = countTokens(prompt)
            check(tokens >= 0) { "Model tokenizer unavailable" }
            if (tokens <= inputBudget) return ContextPlan(prompt, tokens, completed.size - history.size, evidenceCount - evidence)
            when {
                history.size > 1 -> history = history.drop(1)
                evidence > 1 -> evidence--
                history.isNotEmpty() -> history = emptyList()
                evidence > 0 -> throw ContextBudgetExceeded() // Don't silently turn a source-backed request into chat.
                else -> throw ContextBudgetExceeded()
            }
        }
    }
}
