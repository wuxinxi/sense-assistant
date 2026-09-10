package cn.xxstudy.assistant.engine

import android.util.Log

class LlamaEngine {
    companion object {
        private const val TAG = "LlamaEngine"

        init {
            try {
                // Load the C++ library compiled by CMake
                System.loadLibrary("sense_assistant")
                Log.i(TAG, "Native library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library.", e)
            }
        }
    }

    /**
     * Initialize the Llama context with the given model path and context size.
     * Returns true if successful, false otherwise.
     */
    external fun initContext(modelPath: String, nCtx: Int): Boolean

    /**
     * 手动重置当前的对话上下文缓存，用于开启新对话或切换模型。
     */
    external fun resetSession()

    /**
     * 简单的非流式对话推理生成接口
     */
    external fun generateText(prompt: String, callback: LlamaCallback): String

    /**
     * 主动打断当前正在进行的推理任务
     */
    external fun stopGeneration()
}

interface LlamaCallback {
    fun onToken(token: String)
}
