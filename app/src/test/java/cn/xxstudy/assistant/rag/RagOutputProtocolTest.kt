package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.repository.ConversationPromptBuilder
import org.junit.Assert.*
import org.junit.Test

class RagOutputProtocolTest {
    private val evidence = listOf(KnowledgeMatch("synthetic.md", "介绍", "Hive 是轻量级键值数据库，通过 Box 保存数据。", .9f))

    @Test fun productionPromptDemonstratesIndependentSectionLinesRatherThanOnlyDescribingThem() {
        for (policy in RagAnswerPolicy.entries) {
            val prompt = ConversationPromptBuilder.build("简洁助手", "介绍 Hive",
                RagPromptBuilder.build(evidence, "介绍 Hive", policy), true, true,
                requestPolicy = policy.systemInstruction())
            assertTrue("missing source format example for $policy", prompt.lines().any { it == RagAnswerComposer.SOURCE_MARKER })
            assertEquals(policy == RagAnswerPolicy.FUSION, prompt.lines().any { it == RagAnswerComposer.GENERAL_MARKER })
            assertTrue(prompt.contains("禁止照抄"))
        }
    }

    @Test fun copyingTheProtocolPlaceholderIsStillRejectedAsUnsupported() {
        val answer = RagSourcePresenter.present("【资料说明】\n- 从当前资料提炼一个要点。[1]", evidence, RagAnswerPolicy.FUSION)
        assertTrue(answer.isFallback)
        assertEquals(listOf(RagAnswerIssue.SOURCE_VALIDATION_FAILED), answer.issues)
    }

    @Test fun ordinaryChatStillHasNoRagProtocol() {
        val prompt = ConversationPromptBuilder.build("简洁助手", "你是什么星座", "", true, false)
        assertFalse(prompt.contains("【资料说明】"))
        assertFalse(prompt.contains("从当前资料提炼"))
    }
}
