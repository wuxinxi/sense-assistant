package cn.xxstudy.assistant.rag

import org.junit.Assert.*
import org.junit.Test

class RagGenerationDiagnosticsTest {
    private val matches = listOf(KnowledgeMatch("private-note.md", "private-title", "Hive 是轻量级键值数据库。", .9f))

    @Test fun rejectedGeneralReportsEveryRuleWithoutExportingDraft() {
        val result = RagAnswerComposer.compose("【资料说明】\nHive 是轻量级键值数据库。[1]\n【通用补充】\n根据我的笔记，使用 `Hive.fakeApi()`。[1] rag-source:private", matches, RagAnswerPolicy.FUSION)
        assertEquals(listOf(RagAnswerIssue.GENERAL_VALIDATION_FAILED), result.issues)
        assertEquals(setOf(RagGeneralRejection.CITATION, RagGeneralRejection.SOURCE_URL,
            RagGeneralRejection.PRIVATE_ATTRIBUTION, RagGeneralRejection.PRECISE_IDENTIFIER,
            RagGeneralRejection.CODE), result.generalRejections.toSet())
        assertFalse(result.text.contains("fakeApi"))
    }

    @Test fun permissivePolicyStillCannotAdmitRejectedGeneral() {
        val fixtures = mapOf(
            "概念。[1]" to RagGeneralRejection.CITATION,
            "obsidian:private" to RagGeneralRejection.SOURCE_URL,
            "根据笔记，这个库适合项目。" to RagGeneralRejection.PRIVATE_ATTRIBUTION,
            "使用 googleapis_hive。" to RagGeneralRejection.PRECISE_IDENTIFIER,
            "使用 `键值`。" to RagGeneralRejection.CODE,
            "\"action\": \"private\"" to RagGeneralRejection.ACTION
        )
        for ((draft, reason) in fixtures) {
            val result = RagAnswerComposer.compose("【通用补充】\n$draft", matches, RagAnswerPolicy.FUSION)
            assertTrue(result.isFallback)
            assertTrue(result.generalRejections.contains(reason))
        }
    }

    @Test fun acceptedAndPolicySuppressedGeneralHaveNoValidationReasons() {
        val draft = "【资料说明】\nHive 是轻量级键值数据库。[1]\n【通用补充】\n先明确读写需求。"
        for (policy in RagAnswerPolicy.entries) {
            val result = RagAnswerComposer.compose(draft, matches, policy)
            assertFalse(result.isFallback)
            assertTrue(result.generalRejections.isEmpty())
        }
    }

    @Test fun presenterCarriesReasonCodesToProductionConsumerWithoutDraft() {
        val result = RagSourcePresenter.present("【通用补充】\n使用 googleapis_hive。", matches, RagAnswerPolicy.FUSION)
        assertEquals(listOf(RagGeneralRejection.PRECISE_IDENTIFIER), result.generalRejections)
        assertTrue(result.isFallback)
        assertFalse(result.text.contains("googleapis_hive"))
    }

    @Test fun citationsAndSectionMarkersSurviveEveryStreamSplitInSyntheticFixture() {
        val answer = "【资料说明】\nHive 是轻量级键值数据库。[1]\n【通用补充】\n先明确读写需求。"
        for ((start, end) in listOf("<think>" to "</think>", "<|thought_begin|>" to "<|thought_end|>")) {
            val raw = "$start\nsynthetic reasoning [9]\n$end\n$answer"
            for (split in 0..raw.length) {
                val parser = cn.xxstudy.assistant.engine.ThinkingStreamParser()
                parser.feed(raw.take(split))
                parser.feed(raw.drop(split))
                parser.finish()
                assertEquals("stream split=$split", answer, parser.getSnapshot().answerText)
                val result = RagSourcePresenter.present(parser.getSnapshot().answerText, matches, RagAnswerPolicy.FUSION)
                assertFalse("stream split=$split", result.isFallback)
                assertTrue(result.issues.isEmpty())
            }
        }
    }

    @Test fun outputMeasurementIsFixedNumericMetadataNotAContentCarrier() {
        val metadata = RagGenerationDiagnostics.measure("<think>private [9]</think>\n【资料说明】\nHive。[1]", "【资料说明】\nHive。[1]", "private [9]")
        assertEquals(2, metadata.rawCitations)
        assertEquals(1, metadata.answerCitations)
        assertTrue(metadata.sourceMarker)
        assertFalse(metadata.generalMarker)
        val event = metadata.event()
        assertFalse(event.contains("private"))
        assertFalse(event.contains("Hive"))
        assertFalse(event.contains("[1]"))
        assertTrue(event.matches(Regex("raw_chars=\\d+ answer_chars=\\d+ thinking_chars=\\d+ raw_citations=\\d+ answer_citations=\\d+ source_marker=(?:true|false) general_marker=(?:true|false)")))
    }
}
