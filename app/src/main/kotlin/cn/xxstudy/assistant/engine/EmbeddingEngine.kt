package cn.xxstudy.assistant.engine

import android.util.Log

/**
 * [EmbeddingEngine]
 * 基于 llama.cpp 原生底座的端侧轻量嵌入向量计算引擎。
 * 支持加载 GGUF 格式的文本嵌入模型（如 BGE-small-zh-v1.5，~26MB/48MB）。
 */
object EmbeddingEngine {
    private const val TAG = "EmbeddingEngine"

    @Volatile
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("sense_assistant")
            Log.i(TAG, "Native library loaded successfully for EmbeddingEngine.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library in EmbeddingEngine.", e)
        }
    }

    /**
     * 初始化 Embedding 上下文
     * @param modelPath GGUF 模型的绝对路径
     */
    external fun initContext(modelPath: String): Boolean

    /**
     * 计算文本的 512 维归一化向量 (L2 Normalized)
     * 余弦相似度直接等价于两个向量的点积 (dot product)
     */
    external fun getEmbedding(text: String): FloatArray?

    /**
     * 释放模型与上下文占用的全部内存
     */
    external fun releaseContext()

    fun load(modelPath: String): Boolean {
        synchronized(this) {
            val success = initContext(modelPath)
            isInitialized = success
            return success
        }
    }

    fun isReady(): Boolean = isInitialized

    fun embed(text: String): FloatArray? {
        if (!isInitialized) return null
        return synchronized(this) {
            getEmbedding(text)
        }
    }

    fun close() {
        synchronized(this) {
            releaseContext()
            isInitialized = false
        }
    }
}
