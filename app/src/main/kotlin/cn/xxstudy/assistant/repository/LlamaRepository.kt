package cn.xxstudy.assistant.repository

import cn.xxstudy.assistant.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LlamaRepository] (Repository 模式)
 * 负责封装所有与底层 C++ (JNI) 引擎交互的核心逻辑。
 * 严格遵守分离原则：强制在 IO 线程池执行底层阻塞调用，绝不允许卡顿 UI 主线程。
 */
class LlamaRepository {
    // 实例化底层引擎
    private val engine = LlamaEngine()

    /**
     * 异步加载大模型
     *
     * @param modelPath GGUF 物理文件在安卓系统中的绝对路径
     * @return 是否成功装载进内存
     */
    suspend fun loadModel(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                engine.initContext(modelPath)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * 发送提示词并获取回复，支持流式 token 回调
     */
    suspend fun generateText(prompt: String, onToken: (String) -> Unit): String {
        return withContext(Dispatchers.IO) {
            try {
                engine.generateText(prompt, object : cn.xxstudy.assistant.engine.LlamaCallback {
                    override fun onToken(token: String) {
                        onToken(token)
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
                "底层引擎错误: ${e.message}"
            }
        }
    }

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
}
