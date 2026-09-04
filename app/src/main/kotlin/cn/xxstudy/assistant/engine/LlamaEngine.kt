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
     * Initialize the Llama context with the given model path.
     * Returns true if successful, false otherwise.
     */
    external fun initContext(modelPath: String): Boolean

    /**
     * 简单的非流式对话推理生成接口
     */
    external fun generateText(prompt: String, callback: LlamaCallback): String
}

interface LlamaCallback {
    fun onToken(token: String)
}
