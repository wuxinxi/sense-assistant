package cn.xxstudy.assistant.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.engine.EmbeddingEngine
import cn.xxstudy.assistant.rag.chunker.MarkdownChunker
import cn.xxstudy.assistant.rag.db.ChunkToInsert
import cn.xxstudy.assistant.rag.db.KnowledgeDatabaseHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class SyncProgress(
    val isSyncing: Boolean = false,
    val currentFileName: String = "",
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val newlyIndexedChunks: Int = 0,
    val message: String = ""
)

class ObsidianSyncManager(private val context: Context) {
    companion object {
        private const val TAG = "ObsidianSyncManager"
    }

    private val dbHelper = KnowledgeDatabaseHelper.getInstance(context)
    private val retriever = KnowledgeRetriever(context)
    private val indexState = context.getSharedPreferences("obsidian_index_state", Context.MODE_PRIVATE)

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /**
     * 对齐数据库与设置页统计；数据库升级淘汰旧向量后会立即显示为待重建。
     */
    fun refreshStats() {
        val previousChunkCount = AppSettings.ragChunkCount.value
        val stats = dbHelper.getStats()
        val syncTime = if (stats.second == 0) 0L else AppSettings.ragLastSyncTime.value
        AppSettings.updateRagSyncStats(stats.first, stats.second, syncTime)
        if (previousChunkCount > 0 && stats.second == 0) {
            _progress.value = SyncProgress(message = "向量算法已升级，请重新同步知识库")
        }
    }

    /**
     * 对授权的 Obsidian 根目录执行增量扫描与向量入库
     */
    suspend fun syncVault(treeUri: Uri): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        if (_progress.value.isSyncing) {
            return@withContext Result.failure(IllegalStateException("知识库同步已在运行中"))
        }

        _progress.value = SyncProgress(isSyncing = true, message = "正在检查离线向量模型...")
        try {
            // 1. 确保 Embedding 引擎准备就绪
            if (!retriever.ensureModelLoaded()) {
                _progress.value = SyncProgress(message = "未找到嵌入模型文件 (如 bge-small-zh-v1.5-q8_0.gguf)")
                return@withContext Result.failure(IllegalStateException("未找到嵌入模型文件"))
            }

            _progress.value = SyncProgress(isSyncing = true, message = "正在扫描 Obsidian 目录...")

            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                _progress.value = SyncProgress(message = "无法访问该目录或无权限")
                return@withContext Result.failure(IllegalArgumentException("无法访问选定的知识库目录"))
            }

            // 2. 递归收集所有 .md 笔记
            val mdFiles = mutableListOf<DocumentFile>()
            scanDirectory(rootDoc, mdFiles)

            val totalMdCount = mdFiles.size
            _progress.value = SyncProgress(
                isSyncing = true,
                totalFiles = totalMdCount,
                message = "发现 $totalMdCount 篇 Markdown 笔记，开始增量比对..."
            )

            val recordedDocs = dbHelper.getAllRecordedDocs()
            // No startup wipe or database migration. The next explicit sync
            // replaces each document transactionally; failures keep old chunks.
            val indexedRevision = indexState.getInt("chunker_revision", 1)
            val currentScannedUris = mutableSetOf<String>()
            val failedFiles = mutableListOf<String>()

            var processedCount = 0
            var totalNewChunks = 0

            // 3. 增量索引。元数据不可靠时宁可重建，也不错误跳过。
            for (file in mdFiles) {
                val fileUriStr = file.uri.toString()
                val fileName = file.name ?: "未命名.md"
                val lastModified = file.lastModified()
                val sizeBytes = file.length()
                currentScannedUris.add(fileUriStr)

                val existing = recordedDocs[fileUriStr]
                val isUnchanged = RagIndexPolicy.canSkip(
                    indexedRevision, MarkdownChunker.REVISION,
                    existing?.lastModified, existing?.sizeBytes, lastModified, sizeBytes
                )

                if (isUnchanged) {
                    processedCount++
                    _progress.value = SyncProgress(
                        isSyncing = true,
                        currentFileName = fileName,
                        processedFiles = processedCount,
                        totalFiles = totalMdCount,
                        newlyIndexedChunks = totalNewChunks,
                        message = "[$processedCount/$totalMdCount] $fileName (未变动，跳过)"
                    )
                    continue
                }

                _progress.value = SyncProgress(
                    isSyncing = true,
                    currentFileName = fileName,
                    processedFiles = processedCount,
                    totalFiles = totalMdCount,
                    newlyIndexedChunks = totalNewChunks,
                    message = "[$processedCount/$totalMdCount] 正在切片与向量化: $fileName..."
                )

                try {
                    val content = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                        it.readText()
                    } ?: error("无法读取文件内容")

                    val rawChunks = MarkdownChunker.chunk(fileName, content)
                    val chunksToInsert = rawChunks.map { raw ->
                        val embedding = EmbeddingEngine.embed(raw.text)
                            ?: error("向量模型未能生成 embedding")
                        require(embedding.size == 512) {
                            "embedding 维度异常: expected=512, actual=${embedding.size}"
                        }
                        ChunkToInsert(
                            sectionTitle = raw.sectionTitle,
                            content = raw.text,
                            embedding = embedding
                        )
                    }

                    // 单个事务成功后才替换旧索引；失败时保留上一版可用数据。
                    dbHelper.saveDocumentWithChunks(
                        uri = fileUriStr,
                        name = fileName,
                        lastModified = lastModified,
                        sizeBytes = sizeBytes,
                        chunks = chunksToInsert
                    )
                    totalNewChunks += chunksToInsert.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedFiles.add(fileName)
                    // Do not put private vault filenames in process logs.
                    Log.e(TAG, "Failed to index a vault file; keeping previous index", e)
                }

                processedCount++
            }

            // 4. 只删除本次扫描中确认已经不存在的文档。
            for ((recordedUri, _) in recordedDocs) {
                if (recordedUri !in currentScannedUris) {
                    dbHelper.deleteDocumentByUri(recordedUri)
                }
            }

            val stats = dbHelper.getStats()
            AppSettings.updateRagSyncStats(stats.first, stats.second)

            val failureSuffix = if (failedFiles.isEmpty()) {
                ""
            } else {
                "；${failedFiles.size} 篇失败，将在下次同步重试"
            }
            _progress.value = SyncProgress(
                processedFiles = totalMdCount,
                totalFiles = totalMdCount,
                newlyIndexedChunks = totalNewChunks,
                message = "同步完成：${stats.first} 篇笔记，${stats.second} 个知识分块$failureSuffix"
            )

            Log.i(TAG, "Obsidian sync completed: docs=${stats.first}, chunks=${stats.second}, failures=${failedFiles.size}")
            if (failedFiles.isEmpty()) {
                // Do not mark a partially upgraded vault as current.
                indexState.edit().putInt("chunker_revision", MarkdownChunker.REVISION).apply()
                Result.success(stats)
            } else {
                Result.failure(IllegalStateException("${failedFiles.size} 篇笔记索引失败"))
            }
        } catch (e: CancellationException) {
            _progress.value = _progress.value.copy(message = "同步已取消")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Obsidian sync failed", e)
            _progress.value = _progress.value.copy(message = "同步失败: ${e.message ?: "未知错误"}")
            Result.failure(e)
        } finally {
            if (_progress.value.isSyncing) {
                _progress.value = _progress.value.copy(isSyncing = false)
            }
        }
    }

    /**
     * 递归遍历目录，过滤 .obsidian 插件与隐藏文件
     */
    private fun scanDirectory(dir: DocumentFile, resultList: MutableList<DocumentFile>) {
        val children = dir.listFiles()
        for (child in children) {
            val name = child.name ?: continue
            if (name.startsWith(".")) {
                // 忽略 .obsidian, .trash 等系统隐藏目录
                continue
            }

            if (child.isDirectory) {
                scanDirectory(child, resultList)
            } else if (child.isFile) {
                if (name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)) {
                    resultList.add(child)
                }
            }
        }
    }

    /**
     * 清空全部知识库缓存
     */
    fun clearKnowledgeBase() {
        dbHelper.clearAll()
        AppSettings.updateRagSyncStats(0, 0, 0L)
        _progress.value = SyncProgress(
            isSyncing = false,
            message = "知识库缓存已清空"
        )
    }
}
