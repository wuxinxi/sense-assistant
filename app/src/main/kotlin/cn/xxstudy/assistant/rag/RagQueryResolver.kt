package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.conversation.AnswerKind
import cn.xxstudy.assistant.conversation.ConversationTurn
import cn.xxstudy.assistant.conversation.ConversationRecap
import cn.xxstudy.assistant.conversation.TurnState

enum class RagQueryRoute { SKIP, CURRENT, FOLLOWUP, CLARIFY, RECAP }

data class RagResolvedQuery(
    val query: String,
    val question: String,
    val topic: String,
    val sourceRequired: Boolean,
    val route: RagQueryRoute,
    val clarification: String? = null
) {
    /** Explicit allow-list: new local-only routes must not accidentally enable retrieval. */
    val shouldRetrieve: Boolean get() = query.isNotBlank() && route in setOf(RagQueryRoute.CURRENT, RagQueryRoute.FOLLOWUP)
    val answerPolicy: RagAnswerPolicy get() = if (sourceRequired) RagAnswerPolicy.SOURCE_ONLY else RagAnswerPolicy.FUSION
    fun systemInstruction(hasEvidence: Boolean, ragEnabled: Boolean): String = when {
        !shouldRetrieve -> ""
        hasEvidence -> answerPolicy.systemInstruction()
        ragEnabled -> "本轮没有检索资料依据。可给通用说明，但不得声称来自知识库或猜测私人配置；历史摘要不等于本轮证据。"
        else -> ""
    }
}

/** Conservative user-text-only resolution. Never infer a subject from model output. */
object RagQueryResolver {
    private val smallTalk = Regex("^(?:你好|您好|早上好|晚上好|谢谢|再见|你是谁|你是什么星座|你是什么星座的|讲个笑话)[呀啊呢吗么!！?？。]*$", RegexOption.IGNORE_CASE)
    private val reset = Regex("^(?:换个话题|换一个话题|另一个问题|(?:先)?不聊[^，,。！？!?\\n]+|forget that|new topic)[：:，,。\\s]*", RegexOption.IGNORE_CASE)
    private val referent = Regex("(?:它们?|这个|那个|该(?:库|框架|工具|方法|方案|主题)|上述|前面(?:的)?|刚才(?:的)?|之前(?:的)?)|^(?:继续|再展开|再详细|具体呢|还有呢|初始化呢|配置呢|优缺点呢|(?:那|那么)?(?:怎么|如何)(?:初始化|配置|安装|使用|加密|迁移))|\\b(?:it|its|that|this|continue)\\b", RegexOption.IGNORE_CASE)
    private val returnToSource = Regex("(?:回到|继续).{0,8}(?:刚才|之前|前面).{0,8}(?:知识库|笔记|资料)")
    private val englishWords = setOf("how", "what", "why", "when", "where", "is", "are", "the", "a", "an", "to", "for", "of", "in", "on", "and", "or", "vs", "versus", "it", "its", "this", "that", "please", "explain", "introduce", "use", "using", "initialize", "configure", "continue", "more", "about", "tell", "me", "with", "my", "our", "do", "does", "can", "you")
    private val qualifiers = setOf("flutter", "dart", "android", "ios", "kotlin", "java", "swift", "javascript", "typescript")
    private val attributeWords = setOf("api", "sdk", "json", "http", "https")

    fun resolve(raw: String, history: List<ConversationTurn>): RagResolvedQuery {
        val question = raw.trim()
        if (ConversationRecap.isRequest(question)) return RagResolvedQuery("", question, "", false, RagQueryRoute.RECAP)
        val changedTopic = reset.containsMatchIn(question)
        val currentQuestion = if (changedTopic) question.replaceFirst(reset, "").trim() else question
        if (changedTopic && currentQuestion.isBlank()) {
            return RagResolvedQuery("", question, "", false, RagQueryRoute.CLARIFY, "好的，不再沿用上一轮主题。你想聊什么新主题？")
        }
        val normalized = RagQueryNormalizer.normalize(currentQuestion)
        val required = requiresSources(question)
        if (smallTalk.matches(question)) return RagResolvedQuery("", question, "", false, RagQueryRoute.SKIP)
        val named = topics(normalized)
        val topic = named.joinToString(" / ")
        val isFollowup = referent.containsMatchIn(normalized) ||
            Regex("^(?:那|那么)?(?:初始化|配置|安装|使用|加密|迁移|优缺点|性能|兼容性)(?:呢|怎样|怎么样)?[?？。]*$").matches(normalized) ||
            (required && Regex("^(?:回答|重新回答|再回答)[。!！?？]*$").matches(normalized))
        // An explicit new subject wins. A comparison with an unresolved pronoun does not.
        val pronounComparison = Regex("(?:它|这个|那个|\\bit\\b).{0,8}(?:和|与|跟|vs|versus)", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        if (named.isNotEmpty() && !pronounComparison) {
            return RagResolvedQuery(normalized, question, topic, required, RagQueryRoute.CURRENT)
        }
        if (changedTopic && named.isEmpty() && isFollowup) {
            return RagResolvedQuery("", question, "", required, RagQueryRoute.CLARIFY, "已经切换话题；请说明新主题，我不会沿用上一轮资料。")
        }
        if (changedTopic || !isFollowup) {
            return RagResolvedQuery(normalized, question, topic, required, RagQueryRoute.CURRENT)
        }
        val available = history.filter {
            it.state == TurnState.COMPLETED && it.kind != AnswerKind.USER_RECAP && !ConversationRecap.isRequest(it.question)
        }.takeLast(8)
        val previous = if (returnToSource.containsMatchIn(question)) {
            available.lastOrNull { it.kind in setOf(AnswerKind.SOURCE_BACKED, AnswerKind.SOURCE_LOOKUP, AnswerKind.MIXED) }
        } else available.lastOrNull()
        val previousTopics = previous?.let {
            if (it.retrievalTopic.isNotBlank()) it.retrievalTopic.split(" / ") else topics(RagQueryNormalizer.normalize(it.question))
        }.orEmpty().filter { it.isNotBlank() && it.length <= 80 }
        if (previous == null || previous.kind in setOf(AnswerKind.STATUS, AnswerKind.ACTION_PROPOSAL, AnswerKind.CLARIFICATION) || previousTopics.size != 1 || pronounComparison) {
            val clarification = when {
                previousTopics.size > 1 -> "你指的是 ${previousTopics.joinToString("、")} 中的哪一个？请写明主题，我再继续查询。"
                pronounComparison -> "你想把哪个主题与 ${named.joinToString("、").ifBlank { "另一个主题" }} 比较？请写明两个名称。"
                else -> "你指的是哪个主题或哪篇笔记？请补充名称，我再继续回答。"
            }
            return RagResolvedQuery("", question, "", required, RagQueryRoute.CLARIFY, clarification)
        }
        val inherited = previousTopics.single()
        return RagResolvedQuery("$inherited $normalized", "承接主题：$inherited\n当前问题：$question", inherited,
            required || previous.sourceRequired || requiresSources(previous.question), RagQueryRoute.FOLLOWUP)
    }

    fun requiresSources(question: String): Boolean = RagQueryNormalizer.isExplicitKnowledgeLookup(question) ||
        Regex("(?:只|仅|严格|完全).{0,10}(?:资料|笔记|知识库)|(?:根据|依据|按照|按).{0,8}(?:资料|笔记|知识库)|(?:我|我们|本地|你连接的).{0,6}(?:笔记|知识库|文档|记录)|(?:我的|我们|我).{0,8}(?:项目|系统|服务)(?:里|中|内|的).{0,12}(?:密钥|密码|地址|配置|版本|账号)").containsMatchIn(question)

    internal fun topics(question: String): List<String> {
        val quoted = Regex("[「《\"]([^」》\"\\n]{2,64})[」》\"]").findAll(question).map { it.groupValues[1] }.toList()
        if (quoted.isNotEmpty()) return quoted.distinct().take(3)
        val latin = Regex("(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9_+#.-]{1,48}").findAll(question)
            .map { it.value.substringBefore('.').trimEnd('-', '.') }
            .filterNot { it.lowercase() in englishWords || it.lowercase() in attributeWords || it.length < 2 }
            .distinctBy(String::lowercase).toList()
        if (latin.isNotEmpty()) {
            val primary = latin.filterNot { it.lowercase() in qualifiers }
            return if (primary.size == 1) listOf((latin.filter { it.lowercase() in qualifiers } + primary).joinToString(" "))
                else latin.filterNot { primary.isNotEmpty() && it.lowercase() in qualifiers }.take(3)
        }
        val actionSubject = Regex("^(?:请|帮我|请帮我)?(?:怎么|如何)(?:初始化|配置|安装|使用|加密|迁移)([\\u4e00-\\u9fff]{2,20})[?？。！!]*$")
            .find(question)?.groupValues?.get(1)
        if (actionSubject != null && !Regex("它|这个|那个|该").containsMatchIn(actionSubject) &&
            actionSubject !in setOf("一下", "参数", "才行", "比较好", "比较合适", "比较方便")) return listOf(actionSubject)
        // Chinese named subjects require an explicit introduction form, not arbitrary prose.
        return Regex("^(?:请|帮我|请帮我)?(?:介绍一下|介绍下|介绍|解释|讲解|说说)([\\u4e00-\\u9fff]{2,20})(?:[?？。！!]|$)")
            .find(question)?.groupValues?.get(1)?.takeUnless { it in setOf("一下", "这个", "那个", "它", "刚才的内容") }
            ?.let(::listOf).orEmpty()
    }
}
