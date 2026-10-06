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
    val score: Float,
    val docId: Long? = null
)

class KnowledgeRetriever(private val context: Context) {
    companion object {
        private const val TAG = "KnowledgeRetriever"

        private data class CachedIndex(val syncTime: Long, val chunks: List<StoredChunk>) {
            val search = RagSearchIndex(chunks)
        }

        @Volatile
        private var cachedIndex: CachedIndex? = null
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
    ): List<KnowledgeMatch> = retrieveDetailed(query, topK, threshold).matches

    fun retrieveDetailed(
        query: String,
        topK: Int = AppSettings.ragTopK.value,
        threshold: Float = AppSettings.ragScoreThreshold.value
    ): RagSearchResult {
        if (query.isBlank()) return RagSearchResult(emptyList(), RagSearchMode.EMPTY)

        val t0 = System.currentTimeMillis()
        val index = getIndex()
        var embedTime = 0L
        val result = index.search.search(query, { text ->
            val start = System.currentTimeMillis()
            try {
                if (ensureModelLoaded()) EmbeddingEngine.embed(text) else null
            } finally {
                embedTime = System.currentTimeMillis() - start
            }
        }, topK, threshold)
        if (result.embeddingFailed) Log.w(TAG, "Embedding unavailable; used independent keyword retrieval")
        Log.i(
            TAG,
            "RAG Retrieval done: totalChunks=${index.chunks.size}, hits=${result.matches.size}, " +
                "mode=${result.mode}, incompatibleChunks=${result.incompatibleChunkCount}, " +
                "embedTime=${embedTime}ms, searchTime=${System.currentTimeMillis() - t0 - embedTime}ms"
        )
        return result
    }

    private fun getIndex(): CachedIndex {
        val syncTime = AppSettings.ragLastSyncTime.value
        cachedIndex?.takeIf { it.syncTime == syncTime }?.let { return it }
        return synchronized(cacheLock) {
            cachedIndex?.takeIf { it.syncTime == syncTime } ?: run {
                Log.i(TAG, "Reloading knowledge search index from SQLite...")
                CachedIndex(syncTime, dbHelper.getAllChunks()).also { cachedIndex = it }
            }
        }
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
        val allChunks = getIndex().chunks
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
            Triple(seed.docId, seed.docName, seed.sectionTitle) to seed.score
        }
        return RagSectionExpander.expand(seeds, sectionChunks, maxChars).map { chunk ->
            KnowledgeMatch(
                docName = chunk.docName,
                sectionTitle = chunk.sectionTitle,
                content = chunk.content,
                score = scoresBySection[Triple(chunk.docId, chunk.docName, chunk.sectionTitle)]
                    ?: scoresBySection[Triple(null, chunk.docName, chunk.sectionTitle)] ?: 0f,
                docId = chunk.docId
            )
        }
    }

}
