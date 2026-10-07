package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.conversation.*
import org.junit.Assert.*
import org.junit.Test

class RagQueryResolverTest {
    private fun turn(question: String = "介绍 Hive", topic: String = "", kind: AnswerKind = AnswerKind.SOURCE_BACKED,
        state: TurnState = TurnState.COMPLETED, sourceRequired: Boolean = false) =
        ConversationTurn("one", 1, 2, question, "模型随口提到 Redis", "历史摘要", kind, state, topic, sourceRequired)

    @Test fun uniqueFollowupUsesOnlyUserSubjectNotHallucinatedAssistantEntity() {
        val result = RagQueryResolver.resolve("它怎么初始化？", listOf(turn()))
        assertEquals(RagQueryRoute.FOLLOWUP, result.route)
        assertTrue(result.query.contains("Hive"))
        assertFalse(result.query.contains("Redis"))
        assertTrue(result.question.contains("承接主题：Hive"))
    }

    @Test fun flutterIsAQualifierOfHiveNotAnAmbiguousSecondSubject() {
        val current = RagQueryResolver.resolve("帮我介绍下 flutter 数据 Hive", emptyList())
        assertEquals("flutter Hive", current.topic)
        val next = RagQueryResolver.resolve("它怎么初始化？", listOf(turn(topic = current.topic)))
        assertEquals(RagQueryRoute.FOLLOWUP, next.route)
        assertTrue(next.query.startsWith("flutter Hive"))
    }

    @Test fun persistedResolvedTopicAllowsConsecutiveEllipticalQuestions() {
        val result = RagQueryResolver.resolve("再详细一点", listOf(turn("它怎么初始化？", "Hive")))
        assertEquals(RagQueryRoute.FOLLOWUP, result.route)
        assertEquals("Hive", result.topic)
    }

    @Test fun newExplicitSubjectReplacesOldTopicAndItsSourceOnlyScope() {
        val result = RagQueryResolver.resolve("介绍 SQLite", listOf(turn(sourceRequired = true)))
        assertEquals(RagQueryRoute.CURRENT, result.route)
        assertEquals("SQLite", result.topic)
        assertFalse(result.query.contains("Hive"))
        assertFalse(result.sourceRequired)
    }

    @Test fun namedTopicAfterExplicitResetDoesNotKeepNegatedOldTopic() {
        val result = RagQueryResolver.resolve("不聊 Hive 了，介绍 SQLite", listOf(turn()))
        assertEquals("SQLite", result.topic)
        assertFalse(result.query.contains("Hive"))
    }

    @Test fun resetWithAnUnresolvedPronounClarifiesInsteadOfBorrowingOldTopic() {
        val result = RagQueryResolver.resolve("换个话题，它怎么配置？", listOf(turn()))
        assertEquals(RagQueryRoute.CLARIFY, result.route)
        assertTrue(result.query.isEmpty())
    }

    @Test fun smallTalkSkipsSearchButDoesNotDeleteConversationHistory() {
        val history = listOf(turn())
        val result = RagQueryResolver.resolve("你是什么星座？", history)
        assertEquals(RagQueryRoute.SKIP, result.route)
        assertEquals(1, history.size)
        assertFalse(result.sourceRequired)
    }

    @Test fun afterUnrelatedChatBarePronounDoesNotJumpBackToOldKnowledgeTopic() {
        val result = RagQueryResolver.resolve("它怎么初始化？", listOf(turn(), turn("你是什么星座？", kind = AnswerKind.GENERAL)))
        assertEquals(RagQueryRoute.CLARIFY, result.route)
    }

    @Test fun explicitlyReturningToKnowledgeTopicCanCrossSmallTalkWithinWindow() {
        val result = RagQueryResolver.resolve("回到刚才知识库资料，它怎么初始化？",
            listOf(turn(), turn("你是什么星座？", kind = AnswerKind.GENERAL)))
        assertEquals(RagQueryRoute.FOLLOWUP, result.route)
        assertEquals("Hive", result.topic)
    }

    @Test fun ambiguousPreviousComparisonRequiresAChoice() {
        val result = RagQueryResolver.resolve("它怎么初始化？", listOf(turn("Hive 和 SQLite 对比")))
        assertEquals(RagQueryRoute.CLARIFY, result.route)
        assertTrue(result.clarification!!.contains("Hive、SQLite"))
    }

    @Test fun pronounComparisonDoesNotSilentlyResolveOnlyTheNamedHalf() {
        val result = RagQueryResolver.resolve("它和 SQLite 比较呢？", listOf(turn()))
        assertEquals(RagQueryRoute.CLARIFY, result.route)
        assertTrue(result.query.isEmpty())
    }

    @Test fun emptyOrInterruptedHistoryCannotSupplyAReferent() {
        assertEquals(RagQueryRoute.CLARIFY, RagQueryResolver.resolve("它怎么初始化", emptyList()).route)
        assertEquals(RagQueryRoute.CLARIFY, RagQueryResolver.resolve("它怎么初始化", listOf(turn(state = TurnState.INTERRUPTED))).route)
    }

    @Test fun sourceOnlyAndPrivateQuestionsStaySourceOnlyThroughFollowups() {
        val previous = turn("只按知识库介绍 Hive", "Hive", sourceRequired = true)
        assertTrue(RagQueryResolver.resolve("它怎么初始化", listOf(previous)).sourceRequired)
        listOf("我笔记里的密钥是什么", "根据知识库介绍 Hive", "我的项目里的密码是多少").forEach {
            assertTrue(it, RagQueryResolver.requiresSources(it))
        }
    }

    @Test fun oldLedgerWithoutScopeStillReconstructsPolicyFromOriginalUserRequest() {
        val result = RagQueryResolver.resolve("它怎么初始化", listOf(turn("通过知识库查询 Hive")))
        assertTrue(result.sourceRequired)
        assertEquals("Hive", result.topic)
    }

    @Test fun sourceStatusAndActionProposalAreNotSubjects() {
        for (kind in listOf(AnswerKind.STATUS, AnswerKind.ACTION_PROPOSAL, AnswerKind.CLARIFICATION)) {
            assertEquals(RagQueryRoute.CLARIFY, RagQueryResolver.resolve("继续", listOf(turn(topic = "Hive", kind = kind))).route)
        }
    }

    @Test fun quotedChineseTitleIsAUserOwnedTopic() {
        val result = RagQueryResolver.resolve("它有哪些要点？", listOf(turn("介绍《年度规划》")))
        assertEquals("年度规划", result.topic)
        assertEquals(RagQueryRoute.FOLLOWUP, result.route)
    }

    @Test fun englishPronounIsNotANewEntity() {
        assertEquals(RagQueryRoute.FOLLOWUP, RagQueryResolver.resolve("How do I initialize it?", listOf(turn())).route)
    }

    @Test fun attributeWordDoesNotReplaceTheActualTopic() {
        assertEquals("Hive", RagQueryResolver.resolve("它支持 JSON 吗", listOf(turn())).topic)
    }

    @Test fun topicsOlderThanEightCompletedTurnsAreNotSilentlyRecovered() {
        val history = listOf(turn()) + (1..8).map { turn("你是什么星座？", kind = AnswerKind.GENERAL) }
        assertEquals(RagQueryRoute.CLARIFY, RagQueryResolver.resolve("回到刚才知识库资料，它怎么初始化？", history).route)
    }

    @Test fun sourceScopeNormalizationDoesNotTurnSummaryRequestIntoRawLookup() {
        assertEquals("介绍 Hive", RagQueryNormalizer.normalize("只按知识库介绍 Hive"))
        assertFalse(RagQueryNormalizer.isExplicitKnowledgeLookup("只按知识库介绍 Hive"))
        assertTrue(RagQueryResolver.resolve("只按知识库介绍 Hive", emptyList()).sourceRequired)
    }

    @Test fun sourceOnlyDirectiveCanContinueTheCurrentTopicWithoutInventingNewQuery() {
        val result = RagQueryResolver.resolve("只按笔记回答", listOf(turn()))
        assertEquals(RagQueryRoute.FOLLOWUP, result.route)
        assertEquals("Hive", result.topic)
        assertTrue(result.sourceRequired)
    }

    @Test fun shortAttributeQuestionCanContinueUniqueTopic() {
        for (question in listOf("初始化？", "那配置呢？", "优缺点呢？")) {
            assertEquals(question, RagQueryRoute.FOLLOWUP, RagQueryResolver.resolve(question, listOf(turn())).route)
        }
    }

    @Test fun explicitChineseObjectAfterHowIsNotMistakenForEllipticalFollowup() {
        val result = RagQueryResolver.resolve("如何配置路由器？", listOf(turn()))
        assertEquals(RagQueryRoute.CURRENT, result.route)
        assertEquals("路由器", result.topic)
        assertFalse(result.query.contains("Hive"))
        assertEquals(RagQueryRoute.CURRENT, RagQueryResolver.resolve("如何配置路由器？", emptyList()).route)
    }

    @Test fun stoppingOldTopicWithoutNamingNewOneDoesNotSearchTheNegatedEntity() {
        val result = RagQueryResolver.resolve("不聊 Hive 了", listOf(turn()))
        assertEquals(RagQueryRoute.CLARIFY, result.route)
        assertEquals("", result.query)
        assertEquals("", result.topic)
    }

    @Test fun smallTalkGetsNoCurrentRagRefusalInstructionEvenAfterSourceOnlyTurn() {
        val result = RagQueryResolver.resolve("你是什么星座？", listOf(turn(sourceRequired = true)))
        assertEquals("", result.systemInstruction(false, true))
        val newQuestion = RagQueryResolver.resolve("介绍 SQLite", listOf(turn(sourceRequired = true)))
        assertEquals(RagAnswerPolicy.FUSION, newQuestion.answerPolicy)
        assertFalse(newQuestion.systemInstruction(true, true).contains("只按当前资料"))
    }
}
