package cn.xxstudy.assistant.repository

import org.junit.Assert.*
import org.junit.Test

class ConversationPromptBuilderTest {
    @Test fun zeroHitSmallTalkContainsOnlyTheNormalQuestionAndSystemPrompt() {
        val prompt = ConversationPromptBuilder.build("你是端侧智能助手。", "你是什么星座", "", true, false)
        assertEquals("<|im_start|>system\n你是端侧智能助手。<|im_end|>\n<|im_start|>user\n你是什么星座<|im_end|>\n<|im_start|>assistant\n", prompt)
        assertFalse(prompt.contains("知识库资料不足"))
        assertFalse(prompt.contains("<reference"))
    }

    @Test fun ragRestrictionIsScopedToTheRequestThatHasEvidence() {
        val rag = "只依据资料；不能支持的问题明确说“知识库资料不足”。"
        val prompt = ConversationPromptBuilder.build("普通助手", "介绍 Hive", rag, true, true)
        assertTrue(prompt.startsWith("<|system_cmd_disable_thinking|><|im_start|>system"))
        assertTrue(prompt.contains("$rag\n\n我的问题是：介绍 Hive"))
        val chat = ConversationPromptBuilder.build("普通助手", "你是什么星座", "", true, false)
        assertFalse(chat.contains(rag))
        assertFalse(chat.contains("<|system_cmd_disable_thinking|>"))
    }

    @Test fun continuingOrdinaryChatDoesNotRepeatTheSystemPrompt() {
        val prompt = ConversationPromptBuilder.build("普通助手", "接着聊", "", false, false)
        assertEquals("<|im_start|>user\n接着聊<|im_end|>\n<|im_start|>assistant\n", prompt)
    }
}
