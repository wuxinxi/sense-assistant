package cn.xxstudy.assistant.repository

import cn.xxstudy.assistant.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [LlamaRepository] (Repository 模式)
 * 负责封装所有与底层 C++ (JNI) 引擎交互的核心逻辑。
 * 严格遵守分离原则：强制在 IO 线程池执行底层阻塞调用，绝不允许卡顿 UI 主线程。
 */
class LlamaRepository {
    companion object {
        // C++ model/KV are global, therefore every repository must share one owner.
        private val engine = LlamaEngine()
        private val session = ConversationSession(
            resetNative = { withContext(Dispatchers.IO) { engine.resetSession() } },
            generateNative = { prompt, onToken ->
                withContext(Dispatchers.IO) {
                    val result = engine.generateText(prompt, object : cn.xxstudy.assistant.engine.LlamaCallback {
                        override fun onToken(token: String) = onToken(token)
                    })
                    NativeInferenceFailure.requireSuccess(result)
                }
            }
        )
        private val gate = InferenceRequestGate(
            engine::prepareGeneration,
            engine::stopGeneration,
            invalidateSession = { session.mutateContext {} }
        )
    }
    private val ownerId = UUID.randomUUID().toString()

    /**
     * 异步加载大模型
     *
     * @param modelPath GGUF 物理文件在安卓系统中的绝对路径
     * @param nCtx 多轮对话上下文窗口大小
     * @return 是否成功装载进内存
     */
    suspend fun loadModel(modelPath: String, nCtx: Int, useGpu: Boolean = false): Boolean {
        return gate.mutate {
            session.mutateContext {
                withContext(Dispatchers.IO) {
                    try {
                        engine.initContext(modelPath, nCtx, useGpu)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: UnsatisfiedLinkError) {
                        android.util.Log.e("LlamaRepository", "Native inference library unavailable")
                        false
                    } catch (_: Exception) {
                        android.util.Log.e("LlamaRepository", "Model initialization failed")
                        false
                    }
                }
            }
        }
    }

    suspend fun getBackendName(): String = gate.mutate {
        withContext(Dispatchers.IO) { engine.getBackendName() }
    }

    /**
     * 重置对话上下文，开启新对话
     */
    suspend fun resetSession() {
        gate.mutate { session.reset() }
    }

    /**
     * 发送提示词并获取回复，支持流式 token 回调
     */
    suspend fun generateText(prompt: String, onToken: (String) -> Unit): String {
        return try {
            generateConversation(ConversationMode.EXTERNAL, true, { prompt }, onToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("LlamaRepository", "External generation failed")
            "底层引擎错误：本次生成未完成。"
        }
    }

    suspend fun generateConversation(
        mode: ConversationMode,
        isFirstTurn: Boolean,
        buildPrompt: (Boolean) -> String,
        onToken: (String) -> Unit,
        requestId: String = UUID.randomUUID().toString()
    ): String = gate.generate(ownerId, requestId) { accept, ownerChanged ->
        // A different repository can have touched the global KV; request-local
        // ownership is authoritative. History replay replaces this safe baseline in P1.
        session.generate(mode, isFirstTurn || ownerChanged, buildPrompt) { token -> if (accept(token)) onToken(token) }
    }

    /** Correctness baseline: application history is authoritative; KV is disposable. */
    suspend fun generateReplay(
        mode: ConversationMode,
        history: List<cn.xxstudy.assistant.conversation.ConversationTurn>,
        evidenceCount: Int,
        buildPrompt: (List<cn.xxstudy.assistant.conversation.ConversationTurn>, Int) -> String,
        onPlan: (cn.xxstudy.assistant.conversation.ContextPlan) -> Unit,
        onToken: (String) -> Unit,
        requestId: String
    ): String = gate.generate(ownerId, requestId) { accept, _ ->
        val plan = withContext(Dispatchers.IO) {
            cn.xxstudy.assistant.conversation.ContextPlanner.plan(
                history, evidenceCount, engine.getContextCapacity(), engine::countPromptTokens, buildPrompt
            )
        }
        onPlan(plan)
        session.generate(mode, true, { plan.prompt }) { token -> if (accept(token)) onToken(token) }
    }

    /**
     * 主动打断当前推理
     */
    fun stopGeneration(requestId: String?) { gate.cancel(ownerId, requestId) }

    /**
     * 彻底释放模型与上下文，归还所有显存/内存
     */
    suspend fun unloadModel() {
        gate.mutate {
            session.mutateContext {
                withContext(Dispatchers.IO) {
                    try {
                        engine.releaseContext()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        android.util.Log.e("LlamaRepository", "Model release failed")
                    }
                }
            }
        }
    }
}
