package cn.xxstudy.assistant.rag

/** Debug-only callers may emit these fixed numeric/boolean fields, never the input strings.
 * rawCitations includes reasoning: a difference from answerCitations does NOT prove parser loss.
 */
data class RagOutputMeasurement(
    val rawChars: Int,
    val answerChars: Int,
    val thinkingChars: Int,
    val rawCitations: Int,
    val answerCitations: Int,
    val sourceMarker: Boolean,
    val generalMarker: Boolean
) {
    fun event(): String = "raw_chars=$rawChars answer_chars=$answerChars thinking_chars=$thinkingChars " +
        "raw_citations=$rawCitations answer_citations=$answerCitations " +
        "source_marker=$sourceMarker general_marker=$generalMarker"
}

object RagGenerationDiagnostics {
    private val citation = Regex("\\[\\s*\\d+\\s*]")
    fun measure(rawBody: String, answer: String, thinking: String?): RagOutputMeasurement = RagOutputMeasurement(
        rawBody.length, answer.length, thinking?.length ?: 0,
        citation.findAll(rawBody).count(), citation.findAll(answer).count(),
        answer.lineSequence().any { it.trim() == RagAnswerComposer.SOURCE_MARKER },
        answer.lineSequence().any { it.trim() == RagAnswerComposer.GENERAL_MARKER }
    )
}
