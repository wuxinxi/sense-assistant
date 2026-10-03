package cn.xxstudy.assistant.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGenerationPolicyTest {
    @Test
    fun ragAnswersSkipThinkingToReserveTheBudgetForGroundedText() {
        assertTrue(
            RagGenerationPolicy.shouldDisableThinking(
                userEnabledThinking = true,
                modelSupportsThinking = true,
                hasKnowledgeMatches = true
            )
        )
    }

    @Test
    fun ordinaryQuestionsKeepTheUsersThinkingPreference() {
        assertFalse(
            RagGenerationPolicy.shouldDisableThinking(
                userEnabledThinking = true,
                modelSupportsThinking = true,
                hasKnowledgeMatches = false
            )
        )
    }
}
