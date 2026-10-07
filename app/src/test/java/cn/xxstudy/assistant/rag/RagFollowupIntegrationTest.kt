package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.conversation.*
import cn.xxstudy.assistant.rag.db.StoredChunk
import cn.xxstudy.assistant.repository.ConversationPromptBuilder
import org.junit.Assert.*
import org.junit.Test

/** Production resolver -> real lexical index -> composer -> history, controlled offline corpus. */
class RagFollowupIntegrationTest {
    private val index = RagSearchIndex(listOf(
        StoredChunk(1, 1, "Flutter Hive.md", "初始化", "Flutter Hive 是轻量级键值数据库。初始化：Hive.initFlutter()。", floatArrayOf()),
        StoredChunk(2, 2, "SQLite.md", "概念", "SQLite 是关系型数据库。", floatArrayOf())
    ))
    private fun turn(question: String, topic: String, kind: AnswerKind = AnswerKind.SOURCE_BACKED, required: Boolean = false) =
        ConversationTurn("one", 1, 2, question, "旧答", "历史摘要", kind, TurnState.COMPLETED, topic, required)

    @Test fun conversationRecapAfterHiveAndZodiacNeverSearchesTheKnowledgeBase() {
        val history = listOf(
            turn("帮我介绍下 Flutter 数据 Hive", "Flutter Hive"),
            turn("你是什么星座", "", AnswerKind.GENERAL)
        )
        val resolved = RagQueryResolver.resolve("我们刚刚聊了什么", history)
        // A recap is about actual turns, not a new document query. This is the
        // production resolver consumed by MainViewModel before retrieval.
        assertEquals(RagQueryRoute.RECAP, resolved.route)
        assertTrue("Recap must not supply a document search query", resolved.query.isEmpty())
        var searchCalls = 0
        val matches = if (resolved.shouldRetrieve) {
            searchCalls++
            index.search(resolved.query, { null }, 3, .8f).matches
        } else emptyList()
        assertEquals(0, searchCalls)
        assertTrue(matches.isEmpty())
        val recap = ConversationRecap.build(resolved.question, history)
        assertEquals(history.map { it.question }, recap.entries.map { it.question })
        assertTrue(recap.text.contains("Hive"))
        assertTrue(recap.text.contains("星座"))
        assertFalse(recap.text.contains("SQLite"))
        assertFalse(recap.text.contains("旧答"))
        assertEquals("", resolved.systemInstruction(false, true))
    }

    @Test fun recapDoesNotBecomeADocumentQueryEvenWithoutAnyHistory() {
        assertEquals("", RagQueryResolver.resolve("刚刚我们聊了什么？", emptyList()).query)
    }

    @Test fun recapParaphrasesDoNotBecomeKnowledgeFollowups() {
        for (question in listOf("刚刚我们聊了什么？", "我们刚才聊了什么", "我们之前讨论过哪些话题？",
            "总结一下我们刚才的对话", "回顾一下最近的聊天", "我上一个问题是什么？", "我刚才问了什么？",
            "What did we just talk about?", "Summarize our conversation")) {
            val resolved = RagQueryResolver.resolve(question, listOf(turn("介绍 Hive", "Hive")))
            assertEquals(question, "", resolved.query)
        }
    }

    @Test fun lookupFollowupRetrievesCurrentApiEvidenceRatherThanGuessingFromMemory() {
        val resolved = RagQueryResolver.resolve("它怎么初始化？", listOf(turn("通过知识库查询 Flutter Hive", "Flutter Hive", AnswerKind.SOURCE_LOOKUP, true)))
        assertTrue(resolved.sourceRequired)
        val matches = index.search(resolved.query, { null }, 3, .8f).matches
        assertEquals("Flutter Hive.md", matches.first().docName)
        val composed = RagAnswerComposer.compose("【资料说明】\n通过 `Hive.initFlutter()` 初始化。[1]", matches, RagAnswerPolicy.SOURCE_ONLY)
        assertEquals(AnswerKind.SOURCE_BACKED, composed.kind)
        val sources = RagSourcePresenter.sources(matches)
        assertTrue(RagCitationLinks.linkify(composed.text, sources).contains("rag-source://reference/1"))
    }

    @Test fun newSubjectDoesNotPullOldHiveEvidenceOrOldSourceOnlyPolicy() {
        val resolved = RagQueryResolver.resolve("介绍 SQLite", listOf(turn("只按笔记介绍 Hive", "Hive", required = true)))
        val matches = index.search(resolved.query, { null }, 3, .8f).matches
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it.docName == "SQLite.md" })
        assertFalse(resolved.sourceRequired)
    }

    @Test fun mixedFinalAnswerReplayCannotRebindOldCitationsToNewEvidence() {
        val resolved = RagQueryResolver.resolve("介绍 Flutter Hive", emptyList())
        val matches = index.search(resolved.query, { null }, 3, .8f).matches
        val answer = RagAnswerComposer.compose("【资料说明】\nHive 是轻量级键值数据库。[1]\n【通用补充】\n可先评估读写需求，再决定存储方案。", matches, RagAnswerPolicy.FUSION)
        assertEquals(AnswerKind.MIXED, answer.kind)
        val previous = turn("介绍 Flutter Hive", resolved.topic, answer.kind).copy(answer = answer.text,
            memoryAnswer = ConversationMemory.project(answer.text, answer.kind))
        val next = RagQueryResolver.resolve("你是什么星座？", listOf(previous))
        assertEquals(RagQueryRoute.SKIP, next.route)
        val prompt = ConversationPromptBuilder.build("助手", next.question, "", true, true, listOf(previous))
        assertTrue(prompt.contains("轻量级键值数据库"))
        assertFalse(prompt.contains("[1]"))
        assertFalse(prompt.contains("只依据资料"))
        assertFalse(prompt.contains("<reference"))
    }

    @Test fun topicCanBeResolvedEvenIfHistoryDoesNotFitModelInputBudget() {
        val history = listOf(turn("介绍 Hive", "Hive"))
        val resolved = RagQueryResolver.resolve("它怎么初始化？", history)
        val plan = ContextPlanner.plan(history, 0, 620, { input -> if (input.contains("历史摘要")) 100 else 40 }, { chosen, _ ->
            ConversationPromptBuilder.build("助手", resolved.question, "", true, true, chosen)
        })
        assertEquals(1, plan.droppedTurns)
        assertTrue(plan.prompt.contains("承接主题：Hive"))
        assertTrue(plan.inputTokens <= 76)
    }
}
