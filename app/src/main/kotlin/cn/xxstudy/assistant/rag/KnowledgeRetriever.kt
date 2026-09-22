package cn.xxstudy.assistant.rag

import android.content.Context
import android.util.Log
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.engine.EmbeddingEngine
import cn.xxstudy.assistant.rag.db.KnowledgeDatabaseHelper
import cn.xxstudy.assistant.rag.db.StoredChunk
import java.io.File

data class KnowledgeMatch(
    val docName: String,
    val sectionTitle: String,
    val content: String,
    val score: Float
)

class KnowledgeRetriever(private val context: Context) {
    companion object {
        private const val TAG = "KnowledgeRetriever"

        @Volatile
        private var cachedChunks: List<StoredChunk> = emptyList()
        @Volatile
        private var lastSyncTimeCache: Long = -1L
        private val cacheLock = Any()

        val EMBEDDING_MODEL_NAMES = listOf(
            "bge-small-zh-v1.5-q8_0.gguf",
            "bge-small-zh-v1.5.gguf",
            "bge-small-zh-v1.5-f16.gguf",
            "bge-small-zh-v1.5-q4_k_m.gguf"
        )

        fun findEmbeddingModelFile(context: Context): File? {
            val searchDirs = listOfNotNull(
                context.getExternalFilesDir(null)?.resolve("models"),
                context.getExternalFilesDir(null),
                context.filesDir.resolve("models"),
                context.filesDir
            )

            for (modelName in EMBEDDING_MODEL_NAMES) {
                for (dir in searchDirs) {
                    val file = File(dir, modelName)
                    if (file.exists() && file.isFile && file.length() > 5 * 1024 * 1024) {
                        return file
                    }
                }
            }
            return null
        }
    }

    private val dbHelper = KnowledgeDatabaseHelper.getInstance(context)

    /**
     * 确保嵌入模型已加载
     */
    fun ensureModelLoaded(): Boolean {
        if (EmbeddingEngine.isReady()) return true
        val modelFile = findEmbeddingModelFile(context)
        if (modelFile == null) {
            Log.w(TAG, "Embedding model file not found in storage.")
            return false
        }
        Log.i(TAG, "Loading embedding model: ${modelFile.absolutePath}")
        return EmbeddingEngine.load(modelFile.absolutePath)
    }

    /**
     * 根据用户输入的 query 语义检索最相关的知识切片
     *
     * @param query 用户问题
     * @param topK 最多返回切片数
     * @param threshold 最低相似度阈值 (0.0 ~ 1.0)
     */
    fun retrieve(
        query: String,
        topK: Int = AppSettings.ragTopK.value,
        threshold: Float = AppSettings.ragScoreThreshold.value
    ): List<KnowledgeMatch> {
        if (query.isBlank()) return emptyList()

        if (!ensureModelLoaded()) {
            Log.w(TAG, "Failed to initialize EmbeddingEngine, skipping RAG retrieval.")
            return emptyList()
        }

        val t0 = System.currentTimeMillis()
        val queryEmbedding = EmbeddingEngine.embed(query)
        if (queryEmbedding == null) {
            Log.e(TAG, "Failed to compute query embedding.")
            return emptyList()
        }
        val tEmbed = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val currentSyncTime = AppSettings.ragLastSyncTime.value
        if (cachedChunks.isEmpty() || lastSyncTimeCache != currentSyncTime) {
            synchronized(cacheLock) {
                if (cachedChunks.isEmpty() || lastSyncTimeCache != currentSyncTime) {
                    Log.i(TAG, "Reloading chunks from SQLite into memory cache...")
                    cachedChunks = dbHelper.getAllChunks()
                    lastSyncTimeCache = currentSyncTime
                }
            }
        }
        val allChunks = cachedChunks
        if (allChunks.isEmpty()) {
            return emptyList()
        }

        // 归一化向量下点积即为余弦相似度。维度不一致意味着索引来自
        // 其他模型或旧版本，不能静默使用部分向量。
        var dimensionMismatchCount = 0
        val scoredChunks = allChunks.mapNotNull { chunk ->
            if (chunk.embedding.size != queryEmbedding.size) {
                dimensionMismatchCount++
                null
            } else {
                chunk to dotProduct(queryEmbedding, chunk.embedding)
            }
        }
        if (dimensionMismatchCount > 0) {
            Log.w(TAG, "Ignored $dimensionMismatchCount chunks with incompatible embedding dimensions")
        }

        val ranked = scoredChunks.sortedByDescending { it.second }
        val bestScore = ranked.firstOrNull()?.second
        val adaptiveThreshold = if (bestScore == null) {
            threshold
        } else {
            // 避免把明显弱于最佳结果的尾部切片一起塞入 Prompt。
            maxOf(threshold, bestScore - 0.08f)
        }

        val safeTopK = topK.coerceIn(1, 8)
        val perDocumentCount = mutableMapOf<String, Int>()
        val matches = buildList {
            for ((chunk, score) in ranked) {
                if (score < adaptiveThreshold || size >= safeTopK) break
                val count = perDocumentCount[chunk.docName] ?: 0
                if (count >= 2) continue
                perDocumentCount[chunk.docName] = count + 1
                add(
                    KnowledgeMatch(
                        docName = chunk.docName,
                        sectionTitle = chunk.sectionTitle,
                        content = chunk.content,
                        score = score
                    )
                )
            }
        }

        val tSearch = System.currentTimeMillis() - t1
        Log.i(
            TAG,
            "RAG Retrieval done: totalChunks=${allChunks.size}, hits=${matches.size}, " +
                "bestScore=${bestScore ?: "n/a"}, threshold=$adaptiveThreshold, " +
                "embedTime=${tEmbed}ms, searchTime=${tSearch}ms"
        )
        return matches
    }

    /**
     * 用于“从知识库查询某主题”这类显式查阅请求：语义检索负责定位章节，
     * 随后按数据库顺序补齐章节的全部连续分块，避免答案停在第一小段。
     */
    fun expandMatchedSections(
        seeds: List<KnowledgeMatch>,
        maxChars: Int = 64_000
    ): List<KnowledgeMatch> {
        if (seeds.isEmpty()) return emptyList()
        val allChunks = cachedChunks.ifEmpty { dbHelper.getAllChunks() }
        val sectionChunks = allChunks.map { chunk ->
            RagSectionChunk(
                id = chunk.id,
                docId = chunk.docId,
                docName = chunk.docName,
                sectionTitle = chunk.sectionTitle,
                content = chunk.content
            )
        }
        val scoresBySection = seeds.associate { seed ->
            (seed.docName to seed.sectionTitle) to seed.score
        }
        return RagSectionExpander.expand(seeds, sectionChunks, maxChars).map { chunk ->
            KnowledgeMatch(
                docName = chunk.docName,
                sectionTitle = chunk.sectionTitle,
                content = chunk.content,
                score = scoresBySection[chunk.docName to chunk.sectionTitle] ?: 0f
            )
        }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimensions must match" }
        var sum = 0.0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }
}
