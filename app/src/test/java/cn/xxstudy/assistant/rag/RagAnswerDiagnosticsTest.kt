package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.conversation.AnswerKind
import org.junit.Assert.*
import org.junit.Test

class RagAnswerDiagnosticsTest {
    private val matches = listOf(KnowledgeMatch("Hive.md", "概述", "Hive 是轻量级键值数据库。Hive.initFlutter() 初始化。", .9f))
    private val supported = "Hive 是轻量级键值数据库。[1]"

    private fun present(text: String, policy: RagAnswerPolicy = RagAnswerPolicy.FUSION) =
        RagSourcePresenter.present(text, matches, policy)

    @Test fun emptyAnswerIsDistinguishedFromMalformedProtocol() {
        assertEquals(listOf(RagAnswerIssue.EMPTY_ANSWER), present(" \n ").issues)
        assertEquals(listOf(RagAnswerIssue.INVALID_FORMAT), present("前言\n【资料说明】\n$supported").issues)
    }

    @Test fun thinkingOnlyGenerationIsNotMisdiagnosedAsMissingCitationInAnAppNotice() {
        val notice = cn.xxstudy.assistant.engine.GenerationOutputResolver.resolve("", "private reasoning")
        val result = RagSourcePresenter.present(notice, matches, RagAnswerPolicy.FUSION, hasFinalAnswer = false)
        assertTrue(result.isFallback)
        assertEquals(listOf(RagAnswerIssue.EMPTY_ANSWER), result.issues)
        assertFalse(result.text.contains("private reasoning"))
        assertFalse(result.text.contains(notice))
    }

    @Test fun missingCitationIsDiagnosedWithoutSurfacingTheDraft() {
        val result = present("【资料说明】\n不带编号的敏感草稿")
        assertTrue(result.isFallback)
        assertEquals(listOf(RagAnswerIssue.MISSING_CITATION), result.issues)
        assertFalse(result.text.contains("敏感草稿"))
    }

    @Test fun inventedApiAndUnknownCitationRemainRejected() {
        for (text in listOf("轻量级键值数据库。Hive.fakeApi。[1]", "轻量级键值数据库。[9]")) {
            val result = present("【资料说明】\n$text")
            assertTrue(result.isFallback)
            assertEquals(listOf(RagAnswerIssue.SOURCE_VALIDATION_FAILED), result.issues)
        }
    }

    @Test fun forgedDestinationHasADistinctReason() {
        val result = present("【资料说明】\n$supported [1](https://example.com/fake)")
        assertTrue(result.isFallback)
        assertEquals(listOf(RagAnswerIssue.INVALID_SOURCE_LINK), result.issues)
    }

    @Test fun codeStaysInTheSourceReader() {
        val result = present("【资料说明】\n$supported\n```dart\nHive.initFlutter();\n```")
        assertTrue(result.isFallback)
        assertEquals(listOf(RagAnswerIssue.SOURCE_CODE_BLOCK), result.issues)
    }

    @Test fun rejectedGeneralDoesNotDiscardSupportedSource() {
        val result = present("【资料说明】\n$supported\n【通用补充】\n使用 googleapis_hive。")
        assertFalse(result.isFallback)
        assertEquals(AnswerKind.SOURCE_BACKED, result.kind)
        assertEquals(listOf(RagAnswerIssue.GENERAL_VALIDATION_FAILED), result.issues)
        assertFalse(result.text.contains("googleapis_hive"))
    }

    @Test fun sourceOnlySuppressionIsNotReportedAsGroundingFailure() {
        val result = present("【资料说明】\n$supported\n【通用补充】\n先明确读写需求。", RagAnswerPolicy.SOURCE_ONLY)
        assertEquals(listOf(RagAnswerIssue.GENERAL_SUPPRESSED), result.issues)
        assertFalse(result.isFallback)
    }

    @Test fun acceptedSourceHasNoIssueAndDiagnosticsAreContentFree() {
        assertTrue(present("【资料说明】\n$supported").issues.isEmpty())
        for (issue in RagAnswerIssue.entries) {
            assertFalse(issue.label.contains("Hive"))
            assertFalse(issue.label.contains("example.com"))
        }
    }

    @Test fun rejectedSourceWithAcceptedGeneralStillProducesValidationEvent() {
        val result = present("【资料说明】\n没有编号的私有草稿\n【通用补充】\n先明确读写需求。")
        assertFalse(result.isFallback)
        assertEquals(AnswerKind.GENERAL, result.kind)
        assertEquals("RAG answer validation: kind=GENERAL, issues=MISSING_CITATION", result.validationEvent)
        assertFalse(result.validationEvent!!.contains("私有草稿"))
    }

    @Test fun successfulSourceDoesNotEmitARejectionEvent() {
        assertNull(present("【资料说明】\n$supported").validationEvent)
    }
}
