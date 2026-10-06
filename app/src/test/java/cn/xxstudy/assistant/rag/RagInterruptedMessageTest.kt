package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.viewmodel.ChatMessage
import cn.xxstudy.assistant.viewmodel.finishInterruptedGeneration
import org.junit.Assert.*
import org.junit.Test

class RagInterruptedMessageTest {
    @Test fun interruptedDraftRetainsReadableSourcesButIsNotACompletedAnswer() {
        val sources = listOf(RagSource(listOf(1), "Hive.md", "使用", "资料"))
        val message = ChatMessage(7, false, "生成中的摘要", isStreaming = true,
            knowledgeStage = RagKnowledgeStage.GENERATING, knowledgeSources = sources)
        val stopped = message.finishInterruptedGeneration()
        assertEquals(message.text, stopped.text)
        assertEquals(sources, stopped.knowledgeSources)
        assertNull(stopped.knowledgeStage)
        assertFalse(stopped.isStreaming)
        assertFalse(stopped.isThinking)
        assertTrue(stopped.isKnowledgeExcerpt)
        assertTrue(stopped.metrics!!.contains("引用未核对"))
    }

    @Test fun stoppingBeforeRetrievalOrFirstTokenDoesNotLeaveAnEmptySpinner() {
        val stopped = ChatMessage(1, false, "", isThinking = true, isStreaming = true,
            knowledgeStage = RagKnowledgeStage.RETRIEVING).finishInterruptedGeneration()
        assertEquals("（已打断）", stopped.text)
        assertFalse(stopped.isThinking)
        assertFalse(stopped.isStreaming)
        assertNull(stopped.knowledgeStage)
    }
}
