package cn.xxstudy.assistant.conversation

import cn.xxstudy.assistant.rag.RagSource
import cn.xxstudy.assistant.viewmodel.ChatMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConversationDisplaySnapshotTest {
    private val source = RagSource(listOf(1, 2), "数据库Hive.md", "Flutter使用",
        "```dart\nfinal box = await Hive.openBox('notes');\n```\n原文 [1] 😀")
    private val display = ConversationDisplaySnapshot(listOf(source),
        "混合检索 · 3 个命中 · 64 ms · 摘要检查：资料说明缺少引用", "首字: 1914 ms", false)

    @Test fun generalSupplementRetainsSourcesNoticeAndMetricsAfterDisplayRoundTrip() {
        val live = ChatMessage(2, false, "通用补充\n未得到可核对的资料说明。",
            thinkingText = "不保存的思考", knowledgeSources = display.sources,
            knowledgeRetrievalInfo = display.retrievalInfo, metrics = display.metrics)
        val saved = ConversationDisplayCodec.decode(ConversationDisplayCodec.encode(live.toDisplaySnapshot()))
        val restored = turn(AnswerKind.GENERAL, saved, live.text).toRestoredChatMessages().last()
        assertEquals(live.text, restored.text)
        assertEquals(live.knowledgeSources, restored.knowledgeSources)
        assertEquals(live.knowledgeRetrievalInfo, restored.knowledgeRetrievalInfo)
        assertEquals(live.metrics, restored.metrics)
        assertTrue(restored.isHistoricalKnowledge)
        assertTrue(restored.isReadOnlyContent)
        assertNull(restored.thinkingText)
        assertNull(restored.knowledgeStage)
        assertFalse(restored.isStreaming)
    }

    @Test fun fallbackAlsoRestoresOriginalSourceSnapshot() {
        val restored = turn(AnswerKind.FALLBACK, display.copy(isKnowledgeExcerpt = true))
            .toRestoredChatMessages().last()
        assertEquals(listOf(source), restored.knowledgeSources)
        assertTrue(restored.isKnowledgeExcerpt)
    }

    @Test fun sourceSnapshotIsNotProjectedIntoModelMemory() {
        val turn = turn(AnswerKind.GENERAL, display, "请查看项目文档。")
        assertEquals("请查看项目文档。", ConversationMemory.completed(listOf(turn)).single().memoryAnswer)
        assertFalse(turn.memoryAnswer.contains("openBox"))
        assertFalse(turn.memoryAnswer.contains("摘要检查"))
        val prompt = cn.xxstudy.assistant.repository.ConversationPromptBuilder.build(
            "系统指令", "你是什么星座", "", true, true, listOf(turn))
        assertFalse(prompt.contains("openBox"))
        assertFalse(prompt.contains("摘要检查"))
    }

    @Test fun missingLegacySnapshotDoesNotInventSources() {
        val restored = turn(AnswerKind.SOURCE_BACKED, null).toRestoredChatMessages().last()
        assertTrue(restored.knowledgeSources.isEmpty())
        assertFalse(restored.isHistoricalKnowledge)
        assertTrue(restored.metrics!!.contains("未保存原文快照"))
    }

    @Test fun pendingDoesNotRestoreFinalDisplayEvenIfAStoredSnapshotExists() {
        val restored = turn(AnswerKind.GENERAL, display).copy(state = TurnState.INTERRUPTED)
            .toRestoredChatMessages().last()
        assertTrue(restored.knowledgeSources.isEmpty())
        assertNull(restored.knowledgeRetrievalInfo)
        assertTrue(restored.text.contains("上轮未完成"))
    }

    @Test fun emptyAndNullMetadataAreDistinctAndLargeUnicodeSourceIsNotTruncated() {
        val long = display.copy(sources = listOf(source.copy(content = "中文😀".repeat(16_000))),
            retrievalInfo = "", metrics = null)
        assertEquals(long, ConversationDisplayCodec.decode(ConversationDisplayCodec.encode(long)))
        assertEquals(ConversationDisplaySnapshot(), ConversationDisplayCodec.decode(
            ConversationDisplayCodec.encode(ConversationDisplaySnapshot())))
    }

    @Test fun corruptUnknownOversizedAndTrailingSnapshotsAreRejected() {
        val encoded = ConversationDisplayCodec.encode(display)
        val invalid = listOf(encoded.copyOf(8), encoded + byteArrayOf(0),
            encoded.copyOf().also { it[3] = 99 }, ByteArray(1_048_577))
        invalid.forEach { bytes ->
            try { ConversationDisplayCodec.decode(bytes); fail("Must reject invalid snapshot") }
            catch (_: IllegalArgumentException) { }
            catch (_: IOException) { }
        }
    }

    @Test(expected = IllegalArgumentException::class) fun invalidReferenceIdsCannotBeSaved() {
        ConversationDisplayCodec.encode(display.copy(sources = listOf(source.copy(referenceIds = listOf(0)))))
    }

    private fun turn(kind: AnswerKind, snapshot: ConversationDisplaySnapshot?, answer: String = "正文") =
        ConversationTurn("request", 1, 2, "帮我介绍下 hive 的使用", answer,
            ConversationMemory.project(answer, kind), kind, TurnState.COMPLETED, displaySnapshot = snapshot)
}
