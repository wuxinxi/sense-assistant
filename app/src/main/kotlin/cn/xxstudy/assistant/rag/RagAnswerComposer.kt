package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.conversation.AnswerKind

enum class RagAnswerPolicy {
    SOURCE_ONLY, FUSION;

    fun systemInstruction(): String {
        val rules = when (this) {
            SOURCE_ONLY -> "本轮只按当前资料回答，私人事实不可猜测。正文用独立行【资料说明】开头，每个要点引用本轮编号如[1]。不输出通用补充。资料不足明确说明，不编造API、版本或配置，代码请看原文。"
            FUSION -> "本轮回答区分资料与常识。资料说明用独立行【资料说明】开头，每个要点引用本轮编号如[1]；通用补充可选，用独立行【通用补充】开头，明确不是笔记内容且不能有资料编号。两段各最多3点，不重复。通用段只给概念或建议，不猜用户私人事实，不声称来自笔记，不生成API、依赖、版本或代码。资料不足可省略资料段，禁止假引用。"
        }
        // Demonstrate syntax, never a fabricated source fact or an application-added citation.
        val example = buildString {
            appendLine("输出格式示例（句子只是格式占位，不是资料事实，禁止照抄）：")
            appendLine(RagAnswerComposer.SOURCE_MARKER)
            appendLine("- 从当前资料提炼一个要点。[1]")
            if (this@RagAnswerPolicy == FUSION) {
                appendLine(RagAnswerComposer.GENERAL_MARKER)
                appendLine("- 可选的概念或建议。")
                append("实际回答替换示例句子；资料段引用当前资料编号；无需通用补充时省略整个通用段。")
            } else append("实际回答替换示例句子，只引用当前资料编号；资料不足明确说明，不要照抄占位句或假引用。")
        }
        return "$rules\n$example"
    }
}

enum class RagAnswerBlockType { SOURCE_BACKED, GENERAL }
/** Fixed reason codes only; never include the rejected text or matched identifiers. */
enum class RagGeneralRejection { EMPTY, CITATION, SOURCE_URL, PRIVATE_ATTRIBUTION, PRECISE_IDENTIFIER, CODE, ACTION }
data class RagAnswerBlock(val type: RagAnswerBlockType, val text: String)
/** Content-free diagnostics; a failed syntax/support check is not a proof that a claim is false. */
enum class RagAnswerIssue(val label: String) {
    EMPTY_ANSWER("未生成正文"),
    INVALID_FORMAT("回答格式不符合要求"),
    MISSING_CITATION("资料说明缺少引用"),
    INVALID_SOURCE_LINK("引用链接不符合要求"),
    SOURCE_CODE_BLOCK("摘要含代码块，请查原文"),
    SOURCE_VALIDATION_FAILED("资料说明未通过引用与支持度检查"),
    GENERAL_SUPPRESSED("资料限定，已隐藏通用补充"),
    GENERAL_VALIDATION_FAILED("通用补充未通过边界检查")
}
data class RagComposedAnswer(
    val blocks: List<RagAnswerBlock>,
    val notices: List<String>,
    val issues: List<RagAnswerIssue> = emptyList(),
    val generalRejections: List<RagGeneralRejection> = emptyList()
) {
    val isFallback: Boolean get() = blocks.isEmpty()
    val kind: AnswerKind get() = when {
        blocks.isEmpty() -> AnswerKind.FALLBACK
        blocks.any { it.type == RagAnswerBlockType.SOURCE_BACKED } && blocks.any { it.type == RagAnswerBlockType.GENERAL } -> AnswerKind.MIXED
        blocks.any { it.type == RagAnswerBlockType.SOURCE_BACKED } -> AnswerKind.SOURCE_BACKED
        else -> AnswerKind.GENERAL
    }
    val text: String get() = (blocks.map { block ->
        val heading = if (block.type == RagAnswerBlockType.SOURCE_BACKED)
            "### 资料说明\n\n模型摘要：引用已核对，不保证逐句正确。" else "### 通用补充\n\n非知识库内容，可能不准确。"
        "$heading\n\n${block.text}"
    } + notices).joinToString("\n\n")
}

/** Bounded single-pass protocol. Models cannot authorize a more permissive policy. */
object RagAnswerComposer {
    const val SOURCE_MARKER = "【资料说明】"
    const val GENERAL_MARKER = "【通用补充】"
    private val numericCitation = Regex("\\[\\s*\\d+\\s*]")
    private val privateAttribution = Regex("知识库|(?:根据|依据|按照|来自).{0,10}(?:笔记|资料|文档|记录|原文)|(?:笔记|资料|文档|记录|原文)(?:里|中|内|显示|提到|指出|说|写|记载|表明)|(?:我的|你的|您的|我们|本地).{0,12}(?:项目|配置|密钥|密码|地址|账号|记录|版本|生日|姓名|邮箱|电话|文件|仓库)")
    private val preciseIdentifier = Regex("\\b(?:[a-zA-Z][a-zA-Z0-9]*_[a-zA-Z0-9_]+|[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z_]\\w*|\\d+(?:\\.\\d+){1,3}|[A-Za-z_]\\w*\\s*\\()")
    private const val SOURCE_FAILURE = "未得到可核对的资料说明；可查看下方检索原文，或补充关键词后重试。"

    fun compose(generated: String, matches: List<KnowledgeMatch>, policy: RagAnswerPolicy): RagComposedAnswer {
        require(matches.isNotEmpty()) { "Source composer requires current evidence" }
        val draft = generated.trim()
        if (draft.isBlank()) return RagComposedAnswer(emptyList(), listOf(SOURCE_FAILURE), listOf(RagAnswerIssue.EMPTY_ANSWER))
        val parsed = parse(draft) ?: return RagComposedAnswer(emptyList(), listOf(SOURCE_FAILURE), listOf(RagAnswerIssue.INVALID_FORMAT))
        val accepted = mutableListOf<RagAnswerBlock>()
        val notices = mutableListOf<String>()
        val issues = mutableListOf<RagAnswerIssue>()
        val generalRejections = mutableListOf<RagGeneralRejection>()
        for (block in parsed) {
            when (block.type) {
                RagAnswerBlockType.SOURCE_BACKED -> {
                    val issue = sourceIssue(block.text, matches)
                    if (issue == null) accepted += block else {
                        notices += SOURCE_FAILURE
                        issues += issue
                    }
                }
                RagAnswerBlockType.GENERAL -> when {
                    policy == RagAnswerPolicy.SOURCE_ONLY -> {
                        notices += "本轮仅依据资料，未展示通用补充。"
                        issues += RagAnswerIssue.GENERAL_SUPPRESSED
                    }
                    else -> {
                        val reasons = generalRejections(block.text)
                        if (reasons.isEmpty()) accepted += block else {
                            notices += "通用补充包含引用、私人资料声明或未经资料支持的代码/API/版本，已隐藏。"
                            issues += RagAnswerIssue.GENERAL_VALIDATION_FAILED
                            generalRejections += reasons
                        }
                    }
                }
            }
        }
        if (accepted.isEmpty() && SOURCE_FAILURE !in notices) notices += SOURCE_FAILURE
        return RagComposedAnswer(accepted.toList(), notices.distinct(), issues.distinct(), generalRejections.distinct())
    }

    private fun sourceIssue(text: String, matches: List<KnowledgeMatch>): RagAnswerIssue? = when {
        text.isBlank() || !numericCitation.containsMatchIn(text) -> RagAnswerIssue.MISSING_CITATION
        Regex("\\[\\d+]\\s*\\(").containsMatchIn(text) || hasSourceUrl(text) -> RagAnswerIssue.INVALID_SOURCE_LINK
        text.contains("```") || text.contains("~~~") -> RagAnswerIssue.SOURCE_CODE_BLOCK
        !RagGroundingGuard.isGrounded(text, matches) -> RagAnswerIssue.SOURCE_VALIDATION_FAILED
        else -> null
    }

    private fun generalRejections(text: String): List<RagGeneralRejection> = buildList {
        if (text.isBlank()) add(RagGeneralRejection.EMPTY)
        if (numericCitation.containsMatchIn(text)) add(RagGeneralRejection.CITATION)
        if (hasSourceUrl(text)) add(RagGeneralRejection.SOURCE_URL)
        if (privateAttribution.containsMatchIn(text)) add(RagGeneralRejection.PRIVATE_ATTRIBUTION)
        if (preciseIdentifier.containsMatchIn(text)) add(RagGeneralRejection.PRECISE_IDENTIFIER)
        if (text.contains('`') || text.contains("~~~")) add(RagGeneralRejection.CODE)
        if (text.contains("\"action\"")) add(RagGeneralRejection.ACTION)
    }

    private fun hasSourceUrl(text: String): Boolean = Regex("rag-source:|obsidian:", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun parse(draft: String): List<RagAnswerBlock>? {
        if (draft.isBlank() || draft.length > 24_000) return null
        val lines = draft.lines()
        val hasMarker = lines.any { it.trim() == SOURCE_MARKER || it.trim() == GENERAL_MARKER }
        // A legacy source-only answer may still be safely accepted, never as general knowledge.
        if (!hasMarker) return listOf(RagAnswerBlock(RagAnswerBlockType.SOURCE_BACKED, draft))
        val blocks = mutableListOf<RagAnswerBlock>()
        val seen = mutableSetOf<RagAnswerBlockType>()
        var type: RagAnswerBlockType? = null
        val body = StringBuilder()
        fun flush(): Boolean {
            val active = type ?: return body.isBlank()
            if (body.isBlank()) return false
            blocks += RagAnswerBlock(active, body.toString().trim())
            body.clear()
            return true
        }
        for (line in lines) {
            val next = when (line.trim()) {
                SOURCE_MARKER -> RagAnswerBlockType.SOURCE_BACKED
                GENERAL_MARKER -> RagAnswerBlockType.GENERAL
                else -> null
            }
            if (next != null) {
                if (!flush() || !seen.add(next)) return null
                type = next
            } else {
                if (type == null && line.isNotBlank()) return null
                body.appendLine(line)
            }
        }
        if (!flush()) return null
        return blocks
    }
}
