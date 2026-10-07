package cn.xxstudy.assistant.repository

import cn.xxstudy.assistant.conversation.*
import cn.xxstudy.assistant.rag.KnowledgeMatch
import cn.xxstudy.assistant.rag.RagPromptBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationReplayTest {
    @Test fun ragThenSmallTalkRetainsFinalAnswerButNotOldRagInstructionOrEvidence() = runBlocking<Unit> {
        var nativeContext = ""
        val session = ConversationSession({ nativeContext = "" }, { prompt, _ -> nativeContext += prompt; "回答" })
        val evidence = RagPromptBuilder.build(listOf(KnowledgeMatch("Hive.md", "Box", "RAW_DOCUMENT_ONLY", .9f)))
        session.generate(ConversationMode.RAG, true, {
            ConversationPromptBuilder.build("助手", "介绍Hive", evidence, true, true)
        }, {})
        val answer = "Hive 使用 Box。[1]"
        val completed = ConversationTurn("one", 1, 2, "介绍Hive", answer,
            ConversationMemory.project(answer, AnswerKind.SOURCE_BACKED), AnswerKind.SOURCE_BACKED, TurnState.COMPLETED)
        val prompt = ContextPlanner.plan(listOf(completed), 0, 2048, { 300 }, { history, _ ->
            ConversationPromptBuilder.build("助手", "我刚才问了什么？", "", true, true, history)
        }).prompt
        session.generate(ConversationMode.CHAT, true, { prompt }, {})
        assertTrue(nativeContext.contains("介绍Hive"))
        assertTrue(nativeContext.contains("Hive 使用 Box"))
        assertFalse(nativeContext.contains("RAW_DOCUMENT_ONLY"))
        assertFalse(nativeContext.contains("只依据资料"))
        assertFalse(nativeContext.contains("[1]"))
    }

    @Test fun ordinaryTopicSwitchAndThenRecallRetainsBothFinalExchanges() {
        val turns = listOf(
            ConversationTurn("one", 1, 2, "介绍Hive", "Box", "历史知识库摘要：Box", AnswerKind.SOURCE_BACKED, TurnState.COMPLETED),
            ConversationTurn("two", 3, 4, "你是什么星座", "没有星座", "没有星座", AnswerKind.GENERAL, TurnState.COMPLETED)
        )
        val prompt = ContextPlanner.plan(turns, 0, 2048, { 300 }, { history, _ ->
            ConversationPromptBuilder.build("助手", "回到刚才的Hive", "", true, true, history)
        }).prompt
        assertTrue(prompt.contains("介绍Hive"))
        assertTrue(prompt.contains("你是什么星座"))
        assertTrue(prompt.contains("回到刚才的Hive"))
    }

    @Test fun lookupTopicIsRememberedWithoutCopyingHugeRawDocumentIntoModel() {
        val turn = ConversationTurn("one", 1, 2, "通过知识库查询Hive", "RAW".repeat(10000),
            ConversationMemory.project("RAW".repeat(10000), AnswerKind.SOURCE_LOOKUP), AnswerKind.SOURCE_LOOKUP, TurnState.COMPLETED)
        val prompt = ConversationPromptBuilder.build("助手", "解释刚才的内容", "", true, true, listOf(turn))
        assertTrue(prompt.contains("通过知识库查询Hive"))
        assertTrue(prompt.contains("需要内容时重新检索"))
        assertFalse(prompt.contains("RAW"))
    }
}
