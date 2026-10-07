package cn.xxstudy.assistant.ui.screens

import org.junit.Assert.*
import org.junit.Test

class ChatInputSubmissionTest {
    @Test fun recapReachesTheReceiverWithoutALoadedModel() {
        val sent = mutableListOf<String>()
        val rejected = mutableListOf<ChatInputRejection>()
        val input = "  刚刚我们聊了什么？  "
        assertTrue(submitChatInput(input, true, false, true, rejected::add) { sent += it; true })
        assertEquals(listOf(input), sent)
        assertTrue(rejected.isEmpty())
    }

    @Test fun recapStillWaitsForHistoryRestoration() {
        val rejected = mutableListOf<ChatInputRejection>()
        assertFalse(submitChatInput("我们刚刚聊了什么", false, false, true, rejected::add) { fail(); true })
        assertEquals(listOf(ChatInputRejection.HISTORY_NOT_READY), rejected)
    }

    @Test fun ordinaryQuestionStillRequiresTheEnabledModel() {
        val rejected = mutableListOf<ChatInputRejection>()
        assertFalse(submitChatInput("介绍 Hive", true, false, true, rejected::add) { fail(); true })
        assertEquals(listOf(ChatInputRejection.MODEL_NOT_READY), rejected)
    }

    @Test fun documentQueryContainingRecapWordsCannotBypassTheModelGate() {
        assertFalse(submitChatInput("介绍《我们刚刚聊了什么》这篇笔记", true, false, true, {}) { fail(); true })
    }

    @Test fun disabledEnginePreservesTheExistingEchoMode() {
        var sent = false
        assertTrue(submitChatInput("语音测试", true, false, false, { fail() }) { sent = true; true })
        assertTrue(sent)
    }

    @Test fun receiverRejectionDoesNotAuthorizeClearingTheDraft() {
        assertFalse(submitChatInput("介绍 Hive", true, true, true, { fail() }) { false })
    }

    @Test fun acceptedNormalQuestionIsSentOnceAndUnchanged() {
        val sent = mutableListOf<String>()
        assertTrue(submitChatInput("介绍 Hive", true, true, true, { fail() }) { sent += it; true })
        assertEquals(listOf("介绍 Hive"), sent)
    }

    @Test fun emptyDraftDoesNotCallEitherCallback() {
        assertFalse(submitChatInput(" \n ", false, false, true, { fail() }) { fail(); true })
    }
}
