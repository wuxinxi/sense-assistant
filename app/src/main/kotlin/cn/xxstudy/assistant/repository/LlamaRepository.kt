package cn.xxstudy.assistant.repository

import cn.xxstudy.assistant.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * [LlamaRepository] (Repository 模式)
 * 负责封装所有与底层 C++ (JNI) 引擎交互的核心逻辑。
 * 严格遵守分离原则：强制在 IO 线程池执行底层阻塞调用，绝不允许卡顿 UI 主线程。
 */
class LlamaRepository {
    // 实例化底层引擎
    private val engine = LlamaEngine()
    private val session = ConversationSession(
        resetNative = { withContext(Dispatchers.IO) { engine.resetSession() } },
        generateNative = { prompt, onToken ->
            withContext(Dispatchers.IO) {
                engine.generateText(prompt, object : cn.xxstudy.assistant.engine.LlamaCallback {
                    override fun onToken(token: String) = onToken(token)
                })
            }
        }
    )

    /**
     * 异步加载大模型
     *
     * @param modelPath GGUF 物理文件在安卓系统中的绝对路径
     * @param nCtx 多轮对话上下文窗口大小
     * @return 是否成功装载进内存
     */
    suspend fun loadModel(modelPath: String, nCtx: Int, useGpu: Boolean = false): Boolean {
        return session.mutateContext {
            withContext(Dispatchers.IO) {
                try {
                    engine.initContext(modelPath, nCtx, useGpu)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsatisfiedLinkError) {
                    android.util.Log.e("LlamaRepository", "Native inference library unavailable", e)
                    false
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }

    suspend fun getBackendName(): String = withContext(Dispatchers.IO) {
        engine.getBackendName()
    }

    /**
     * 重置对话上下文，开启新对话
     */
    suspend fun resetSession() {
        session.reset()
    }

    /**
     * 发送提示词并获取回复，支持流式 token 回调
     */
    suspend fun generateText(prompt: String, onToken: (String) -> Unit): String {
        return try {
            generateConversation(ConversationMode.EXTERNAL, false, { prompt }, onToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("LlamaRepository", "External generation failed", e)
            "底层引擎错误：本次生成未完成。"
        }
    }

    suspend fun generateConversation(
        mode: ConversationMode,
        isFirstTurn: Boolean,
        buildPrompt: (Boolean) -> String,
        onToken: (String) -> Unit
    ): String = session.generate(mode, isFirstTurn, buildPrompt, onToken)

    /**
     * 主动打断当前推理
     */
    fun stopGeneration() {
        try {
            engine.stopGeneration()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 彻底释放模型与上下文，归还所有显存/内存
     */
    suspend fun unloadModel() {
        session.mutateContext {
            withContext(Dispatchers.IO) {
                try {
                    engine.releaseContext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
