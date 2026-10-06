package cn.xxstudy.assistant.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConversationMode { CHAT, RAG, EXTERNAL }

/** One atomic reset/prompt/generation transaction around the shared native KV context. */
internal class ConversationSession(
    private val resetNative: suspend () -> Unit,
    private val generateNative: suspend (String, (String) -> Unit) -> String
) {
    private val mutex = Mutex()
    private var lastMode: ConversationMode? = null
    private var needsFreshContext = true

    suspend fun generate(
        mode: ConversationMode,
        isFirstTurn: Boolean,
        buildPrompt: (includeSystem: Boolean) -> String,
        onToken: (String) -> Unit
    ): String = mutex.withLock {
        // The visible UI turn count is not the native context's lifecycle. RAG evidence
        // and instructions are request-local and must not leak into subsequent chat.
        val resetRequired = needsFreshContext || isFirstTurn || mode == ConversationMode.RAG || lastMode != mode
        val prompt = buildPrompt(resetRequired)
        // Cancellation/error at any point leaves a possibly partial native context.
        // Do not reuse it until a new request resets and reinjects its system prompt.
        needsFreshContext = true
        if (resetRequired) resetNative()
        val result = generateNative(prompt, onToken)
        lastMode = mode
        needsFreshContext = result.startsWith("Error:")
        result
    }

    suspend fun <T> mutateContext(action: suspend () -> T): T = mutex.withLock {
        needsFreshContext = true
        lastMode = null
        action()
    }

    suspend fun reset() = mutateContext { resetNative() }
}
