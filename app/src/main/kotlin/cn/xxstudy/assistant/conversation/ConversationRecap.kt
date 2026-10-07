package cn.xxstudy.assistant.conversation

/** Links every recap line to an actual user message, never to model text or KB content. */
data class ConversationRecapEntry(
    val requestId: String,
    val userMessageId: Int,
    val question: String,
    val state: TurnState
)

data class ConversationRecapResult(val text: String, val entries: List<ConversationRecapEntry>) {
    val metrics: String get() = "会话回顾 · ${entries.size} 个提问 · 未检索知识库，未调用模型"
}

/** Bounded, deterministic USER_RECAP. This is an audit of questions, not a generated summary of facts. */
object ConversationRecap {
    private const val RECENT_QUESTIONS = 8
    private const val QUESTION_CHARS = 240
    private val requestPrefix = "(?:请帮我|帮我|请)?(?:告诉我|提醒我|回顾(?:一下|下)?|总结(?:一下|下)?|复述(?:一下|下)?|梳理(?:一下|下)?)?"
    private val recent = "(?:刚刚|刚才|之前|前面|最近)"
    private val people = "(?:我们|咱们|我和你|你和我)"
    // Whole-request matching avoids treating a quoted document title or a new factual query as memory intent.
    private val topicRequest = Regex("^$requestPrefix(?:$recent$people?|$people$recent?)(?:聊|谈|讨论|说)(?:了|过)?(?:什么|啥|哪些(?:内容|话题)|些什么)(?:内容|话题)?(?:来着|呢|啊)?$")
    private val summaryRequest = Regex("^(?:请帮我|帮我|请)?(?:回顾|总结|复述|梳理)(?:一下|下)?$people?(?:$recent|这段|当前)?的?(?:对话|聊天|聊天记录|讨论)(?:内容)?$")
    private val lastQuestion = Regex("^$requestPrefix(?:我)?(?:上一个|上一条|刚才|刚刚|之前)的?(?:问题|提问)(?:是)?(?:什么|啥)(?:来着|呢)?$")
    private val askedQuestion = Regex("^$requestPrefix(?:我|我们)$recent(?:问|问过|问了)(?:什么|啥|哪些问题)(?:来着|呢)?$")
    private val englishRequest = Regex("^(?:please )?(?:what did (?:we (?:just )?(?:talk|chat|discuss)(?: about)?|i (?:just )?ask(?: you)?)|(?:recap|summarize)(?: our)? (?:recent )?(?:conversation|chat))$", RegexOption.IGNORE_CASE)
    private val englishLastQuestion = Regex("^(?:please )?what did i (?:just )?ask(?: you)?$", RegexOption.IGNORE_CASE)

    private fun compact(question: String) = question.trim().replace(Regex("\\s+"), "")
        .trimEnd('?', '？', '!', '！', '.', '。')
    private fun englishCompact(question: String) = question.trim().replace(Regex("\\s+"), " ").trimEnd('?', '!', '.')

    fun isRequest(question: String): Boolean {
        val chinese = compact(question)
        return topicRequest.matches(chinese) || summaryRequest.matches(chinese) ||
            lastQuestion.matches(chinese) || askedQuestion.matches(chinese) ||
            englishRequest.matches(englishCompact(question))
    }

    fun build(question: String, history: List<ConversationTurn>, currentRequestId: String? = null): ConversationRecapResult {
        require(isRequest(question)) { "Not a conversation recap request" }
        val chinese = compact(question)
        val lastOnly = lastQuestion.matches(chinese) ||
            (askedQuestion.matches(chinese) && !chinese.contains("哪些问题")) ||
            englishLastQuestion.matches(englishCompact(question))
        // The current request may already be in the ledger. Do not include it or anything after it.
        val currentIndex = currentRequestId?.let { id -> history.indexOfFirst { it.requestId == id } } ?: -1
        val previous = if (currentIndex >= 0) history.take(currentIndex) else history
        val entries = previous.filter {
            it.state != TurnState.PENDING && it.question.isNotBlank() &&
                it.kind != AnswerKind.USER_RECAP && !isRequest(it.question)
        }.takeLast(if (lastOnly) 1 else RECENT_QUESTIONS).map {
            ConversationRecapEntry(it.requestId, it.userMessageId, it.question, it.state)
        }
        val text = if (entries.isEmpty()) {
            "当前保存的聊天记录里，没有可回顾的先前问题；我不会用知识库内容补写聊天记录。"
        } else buildString {
            append(if (lastOnly) "你上一个问题是：" else "根据当前保存的聊天记录，最近问过（按先后顺序，最多 $RECENT_QUESTIONS 个）：")
            entries.forEachIndexed { index, entry ->
                append("\n${index + 1}. 「${displayQuestion(entry.question)}」")
                when (entry.state) {
                    TurnState.INTERRUPTED -> append("（该轮回答已中断）")
                    TurnState.FAILED -> append("（该轮回答失败）")
                    else -> Unit
                }
            }
            append("\n\n以上只复述你的提问，不代表回答均正确；没有重新查询知识库。")
        }
        return ConversationRecapResult(text, entries)
    }

    /** UI renders this as literal text. Remove invisible controls and mark truncation explicitly. */
    private fun displayQuestion(question: String): String {
        val line = question.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim()
        if (line.length <= QUESTION_CHARS) return line
        val length = if (line[QUESTION_CHARS - 1].isHighSurrogate()) QUESTION_CHARS - 1 else QUESTION_CHARS
        return line.take(length) + "…（提问较长，已截短；原文见聊天记录）"
    }
}
