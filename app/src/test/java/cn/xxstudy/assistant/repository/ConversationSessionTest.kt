package cn.xxstudy.assistant.repository

import cn.xxstudy.assistant.rag.KnowledgeMatch
import cn.xxstudy.assistant.rag.RagPromptBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
    private class RecordingEngine {
        var context = ""
        val requests = mutableListOf<String>()
        var resetCount = 0
        val session = ConversationSession(
            resetNative = { context = ""; resetCount++ },
            generateNative = { prompt, _ ->
                requests += prompt
                context += prompt
                // Simulates the observed refusal iff the RAG-only restriction remains in KV.
                if (context.contains("知识库资料不足")) "知识库资料不足。" else "普通聊天回答"
            }
        )

        suspend fun ask(mode: ConversationMode, question: String, first: Boolean = false): String {
            val rag = if (mode == ConversationMode.RAG) RagPromptBuilder.build(
                listOf(KnowledgeMatch("Hive.md", "使用", "Hive 使用 Box 存储数据。", .9f)), question
            ) else ""
            return session.generate(mode, first, { includeSystem ->
                ConversationPromptBuilder.build("你是端侧智能助手。", question, rag, includeSystem, mode == ConversationMode.RAG)
            }, {})
        }
    }

    @Test fun ragThenZeroHitSmallTalkHasNoKnowledgeOnlyRuleInItsActiveContext() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.RAG, "介绍 Hive", first = true)
        assertTrue(engine.context.contains("知识库资料不足"))
        val reply = engine.ask(ConversationMode.CHAT, "你是什么星座")
        assertFalse("ordinary chat inherited previous RAG refusal: $reply", reply.contains("知识库资料不足"))
        assertFalse(engine.context.contains("只依据资料"))
        assertTrue(engine.requests.last().contains("<|im_start|>system"))
    }

    @Test fun consecutiveOrdinaryTurnsKeepTheirConversation() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.CHAT, "我叫小唐", first = true)
        engine.ask(ConversationMode.CHAT, "我叫什么")
        assertTrue(engine.context.contains("我叫小唐"))
        assertFalse(engine.requests.last().contains("<|im_start|>system"))
    }

    @Test fun consecutiveRagTurnsDoNotInheritPreviousEvidence() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.RAG, "上一轮主题", first = true)
        engine.ask(ConversationMode.RAG, "下一轮主题")
        assertFalse(engine.context.contains("上一轮主题"))
        assertEquals(2, engine.resetCount)
    }

    @Test fun newNativeContextGetsASystemPromptEvenWithOldVisibleChatHistory() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.CHAT, "之前的对话", first = true)
        engine.session.reset()
        engine.ask(ConversationMode.CHAT, "你是什么星座", first = false)
        assertTrue("UI turn count is not native KV initialization state", engine.requests.last().contains("<|im_start|>system"))
    }

    @Test fun firstInferenceIsFreshEvenIfEarlierUiStatusRepliesWereNotGenerated() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.CHAT, "你是什么星座", first = false)
        assertTrue(engine.requests.single().contains("<|im_start|>system"))
        assertEquals(1, engine.resetCount)
    }

    @Test fun interruptedRagCannotLeaveRestrictionsForTheNextChat() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var context = ""
        var callCount = 0
        val session = ConversationSession({ context = "" }, { prompt, _ ->
            context += prompt
            if (callCount++ == 0) { entered.complete(Unit); release.await() }
            "answer"
        })
        val rag = launch {
            session.generate(ConversationMode.RAG, false, { "RAG: 只依据资料，知识库资料不足" }, {})
        }
        entered.await()
        rag.cancelAndJoin()
        session.generate(ConversationMode.CHAT, false, { includeSystem ->
            assertTrue(includeSystem)
            "你是什么星座"
        }, {})
        assertEquals("你是什么星座", context)
    }

    @Test fun resetAndPromptConstructionCannotInterleaveWithAnotherGeneration() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var context = ""
        var calls = 0
        var resets = 0
        val session = ConversationSession({ resets++; context = "" }, { prompt, _ ->
            context += prompt
            if (calls++ == 0) { entered.complete(Unit); release.await() }
            "answer"
        })
        val first = async { session.generate(ConversationMode.RAG, false, { "RAG only" }, {}) }
        entered.await()
        var builtSecondPrompt = false
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            session.generate(ConversationMode.CHAT, false, { includeSystem ->
                assertTrue(includeSystem)
                builtSecondPrompt = true
                "normal chat"
            }, {})
        }
        assertFalse(builtSecondPrompt)
        assertEquals(1, resets)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals("normal chat", context)
        assertEquals(2, resets)
    }

    @Test fun failedContextResetMustNotProceedIntoGeneration() = runBlocking {
        var failReset = true
        var generated = false
        val session = ConversationSession({
            if (failReset) throw IllegalStateException("fixture reset failure")
        }, { _, _ -> generated = true; "answer" })
        try {
            session.generate(ConversationMode.RAG, false, { "RAG" }, {})
            fail("failed reset must propagate")
        } catch (_: IllegalStateException) { }
        assertFalse(generated)
        failReset = false
        session.generate(ConversationMode.CHAT, false, { includeSystem -> assertTrue(includeSystem); "CHAT" }, {})
        assertTrue(generated)
    }

    @Test fun modelMutationInvalidatesContextEvenWhenItFails() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.CHAT, "旧模型对话", first = true)
        try {
            engine.session.mutateContext<Unit> { throw IllegalStateException("fixture load failure") }
        } catch (_: IllegalStateException) { }
        engine.ask(ConversationMode.CHAT, "新请求")
        assertTrue(engine.requests.last().contains("<|im_start|>system"))
        assertFalse(engine.context.contains("旧模型对话"))
    }

    @Test fun externalApiAndUiNeverInheritEachOthersInstructions() = runBlocking {
        val engine = RecordingEngine()
        engine.ask(ConversationMode.RAG, "知识库请求", first = true)
        engine.session.generate(ConversationMode.EXTERNAL, false, { "external caller instruction" }, {})
        assertFalse(engine.context.contains("知识库资料不足"))
        engine.ask(ConversationMode.CHAT, "你是什么星座")
        assertFalse(engine.context.contains("external caller instruction"))
        assertTrue(engine.requests.last().contains("<|im_start|>system"))
    }

    @Test fun externalConsecutiveRequestsKeepTheExistingApiConversationBehavior() = runBlocking {
        val engine = RecordingEngine()
        engine.session.generate(ConversationMode.EXTERNAL, false, { "external first" }, {})
        engine.session.generate(ConversationMode.EXTERNAL, false, { "external second" }, {})
        assertEquals("external firstexternal second", engine.context)
        assertEquals(1, engine.resetCount)
    }

    @Test fun exceptionAfterPartialNativeOutputAlsoForcesAFreshChat() = runBlocking {
        var failGeneration = true
        var context = ""
        val session = ConversationSession({ context = "" }, { prompt, _ ->
            context += prompt
            if (failGeneration) throw IllegalStateException("fixture generation error")
            "answer"
        })
        try {
            session.generate(ConversationMode.CHAT, true, { "partial previous prompt" }, {})
        } catch (_: IllegalStateException) { }
        failGeneration = false
        session.generate(ConversationMode.CHAT, false, { includeSystem -> assertTrue(includeSystem); "fresh chat" }, {})
        assertEquals("fresh chat", context)
    }
}
