package cn.xxstudy.assistant.ui.screens

import cn.xxstudy.assistant.conversation.ConversationRecap

internal enum class ChatInputRejection { HISTORY_NOT_READY, MODEL_NOT_READY }

/** One submission boundary: callers clear the draft only when the receiver accepts it. */
internal fun submitChatInput(
    input: String,
    historyReady: Boolean,
    modelReady: Boolean,
    engineEnabled: Boolean,
    onRejected: (ChatInputRejection) -> Unit,
    onSend: (String) -> Boolean
): Boolean {
    if (input.isBlank()) return false
    if (!historyReady) {
        onRejected(ChatInputRejection.HISTORY_NOT_READY)
        return false
    }
    if (engineEnabled && !modelReady && !ConversationRecap.isRequest(input)) {
        onRejected(ChatInputRejection.MODEL_NOT_READY)
        return false
    }
    return onSend(input)
}
