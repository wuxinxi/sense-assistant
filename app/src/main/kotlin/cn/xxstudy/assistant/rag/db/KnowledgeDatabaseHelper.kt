package cn.xxstudy.assistant.rag.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class StoredChunk(
    val id: Long,
    val docId: Long,
    val docName: String,
    val sectionTitle: String,
    val content: String,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StoredChunk
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class ChunkToInsert(
    val sectionTitle: String,
    val content: String,
    val embedding: FloatArray
)

data class RecordedDocument(
    val id: Long,
    val lastModified: Long,
    val sizeBytes: Long
)

class KnowledgeDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "obsidian_knowledge.db"
        // Version 2 invalidates vectors generated with the old, incorrect
        // Mean Pooling configuration and adds file-size metadata.
        private const val DB_VERSION = 2

        const val TABLE_DOCUMENTS = "documents"
        const val COL_DOC_ID = "id"
        const val COL_DOC_URI = "uri"
        const val COL_DOC_NAME = "name"
        const val COL_DOC_LAST_MODIFIED = "last_modified"
        const val COL_DOC_SIZE_BYTES = "size_bytes"
        const val COL_DOC_CHUNK_COUNT = "chunk_count"

        const val TABLE_CHUNKS = "chunks"
        const val COL_CHUNK_ID = "id"
        const val COL_CHUNK_DOC_ID = "doc_id"
        const val COL_CHUNK_DOC_NAME = "doc_name"
        const val COL_CHUNK_SECTION_TITLE = "section_title"
        const val COL_CHUNK_CONTENT = "content"
        const val COL_CHUNK_EMBEDDING = "embedding"

        @Volatile
        private var instance: KnowledgeDatabaseHelper? = null

        fun getInstance(context: Context): KnowledgeDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: KnowledgeDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }

        fun floatArrayToBlob(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) {
                buffer.putFloat(f)
            }
            return buffer.array()
        }

        fun blobToFloatArray(bytes: ByteArray): FloatArray {
            val floatCount = bytes.size / 4
            val floats = FloatArray(floatCount)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until floatCount) {
                floats[i] = buffer.float
            }
            return floats
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_DOCUMENTS (
                $COL_DOC_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DOC_URI TEXT UNIQUE NOT NULL,
                $COL_DOC_NAME TEXT NOT NULL,
                $COL_DOC_LAST_MODIFIED INTEGER NOT NULL,
                $COL_DOC_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $COL_DOC_CHUNK_COUNT INTEGER DEFAULT 0
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_CHUNKS (
                $COL_CHUNK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CHUNK_DOC_ID INTEGER NOT NULL,
                $COL_CHUNK_DOC_NAME TEXT NOT NULL,
                $COL_CHUNK_SECTION_TITLE TEXT,
                $COL_CHUNK_CONTENT TEXT NOT NULL,
                $COL_CHUNK_EMBEDDING BLOB NOT NULL,
                FOREIGN KEY ($COL_CHUNK_DOC_ID) REFERENCES $TABLE_DOCUMENTS($COL_DOC_ID) ON DELETE CASCADE
            );
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_chunks_doc_id ON $TABLE_CHUNKS ($COL_CHUNK_DOC_ID);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHUNKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DOCUMENTS")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /**
     * 获取数据库中已记录的所有文档元信息。
     */
    fun getAllRecordedDocs(): Map<String, RecordedDocument> {
        val result = mutableMapOf<String, RecordedDocument>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOCUMENTS,
            arrayOf(COL_DOC_ID, COL_DOC_URI, COL_DOC_LAST_MODIFIED, COL_DOC_SIZE_BYTES),
            null, null, null, null, null
        )
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(COL_DOC_ID)
            val uriIdx = cursor.getColumnIndexOrThrow(COL_DOC_URI)
            val modIdx = cursor.getColumnIndexOrThrow(COL_DOC_LAST_MODIFIED)
            val sizeIdx = cursor.getColumnIndexOrThrow(COL_DOC_SIZE_BYTES)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val uri = cursor.getString(uriIdx)
                val mtime = cursor.getLong(modIdx)
                result[uri] = RecordedDocument(
                    id = id,
                    lastModified = mtime,
                    sizeBytes = cursor.getLong(sizeIdx)
                )
            }
        }
        return result
    }

    /**
     * 插入或更新文档记录，并覆盖写入对应的分块
     */
    fun saveDocumentWithChunks(
        uri: String,
        name: String,
        lastModified: Long,
        sizeBytes: Long,
        chunks: List<ChunkToInsert>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. 查询是否存在旧记录
            var docId: Long = -1
            val cursor = db.query(
                TABLE_DOCUMENTS,
                arrayOf(COL_DOC_ID),
                "$COL_DOC_URI = ?",
                arrayOf(uri),
                null, null, null
            )
            cursor.use {
                if (cursor.moveToFirst()) {
                    docId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DOC_ID))
                }
            }

            if (docId != -1L) {
                // 删除旧的 chunks
                db.delete(TABLE_CHUNKS, "$COL_CHUNK_DOC_ID = ?", arrayOf(docId.toString()))
                // 更新文档信息
                val values = ContentValues().apply {
                    put(COL_DOC_NAME, name)
                    put(COL_DOC_LAST_MODIFIED, lastModified)
                    put(COL_DOC_SIZE_BYTES, sizeBytes)
                    put(COL_DOC_CHUNK_COUNT, chunks.size)
                }
                db.update(TABLE_DOCUMENTS, values, "$COL_DOC_ID = ?", arrayOf(docId.toString()))
            } else {
                // 插入新文档记录
                val values = ContentValues().apply {
                    put(COL_DOC_URI, uri)
                    put(COL_DOC_NAME, name)
                    put(COL_DOC_LAST_MODIFIED, lastModified)
                    put(COL_DOC_SIZE_BYTES, sizeBytes)
                    put(COL_DOC_CHUNK_COUNT, chunks.size)
                }
                docId = db.insert(TABLE_DOCUMENTS, null, values)
                check(docId != -1L) { "Failed to insert document metadata: $uri" }
            }

            // 2. 批量插入 chunks
            for (chunk in chunks) {
                val chunkValues = ContentValues().apply {
                    put(COL_CHUNK_DOC_ID, docId)
                    put(COL_CHUNK_DOC_NAME, name)
                    put(COL_CHUNK_SECTION_TITLE, chunk.sectionTitle)
                    put(COL_CHUNK_CONTENT, chunk.content)
                    put(COL_CHUNK_EMBEDDING, floatArrayToBlob(chunk.embedding))
                }
                check(db.insert(TABLE_CHUNKS, null, chunkValues) != -1L) {
                    "Failed to insert chunk for document: $uri"
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 移除已在外部被删除的文档及其所有分块
     */
    fun deleteDocumentByUri(uri: String) {
        val db = writableDatabase
        db.delete(TABLE_DOCUMENTS, "$COL_DOC_URI = ?", arrayOf(uri))
    }

    /**
     * 获取数据库中的所有分块及其向量（用于全量内存余弦相似度检索）
     */
    fun getAllChunks(): List<StoredChunk> {
        val list = mutableListOf<StoredChunk>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CHUNKS,
            arrayOf(
                COL_CHUNK_ID,
                COL_CHUNK_DOC_ID,
                COL_CHUNK_DOC_NAME,
                COL_CHUNK_SECTION_TITLE,
                COL_CHUNK_CONTENT,
                COL_CHUNK_EMBEDDING
            ),
            null, null, null, null, null
        )
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(COL_CHUNK_ID)
            val docIdIdx = cursor.getColumnIndexOrThrow(COL_CHUNK_DOC_ID)
            val docNameIdx = cursor.getColumnIndexOrThrow(COL_CHUNK_DOC_NAME)
            val secIdx = cursor.getColumnIndexOrThrow(COL_CHUNK_SECTION_TITLE)
            val contentIdx = cursor.getColumnIndexOrThrow(COL_CHUNK_CONTENT)
            val embedIdx = cursor.getColumnIndexOrThrow(COL_CHUNK_EMBEDDING)

            while (cursor.moveToNext()) {
                val blob = cursor.getBlob(embedIdx)
                val floats = blobToFloatArray(blob)
                list.add(
                    StoredChunk(
                        id = cursor.getLong(idIdx),
                        docId = cursor.getLong(docIdIdx),
                        docName = cursor.getString(docNameIdx),
                        sectionTitle = cursor.getString(secIdx) ?: "",
                        content = cursor.getString(contentIdx),
                        embedding = floats
                    )
                )
            }
        }
        return list
    }

    /**
     * 获取统计数据：Pair(文档数, 切片数)
     */
    fun getStats(): Pair<Int, Int> {
        val db = readableDatabase
        var docCount = 0
        var chunkCount = 0

        val docCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DOCUMENTS", null)
        docCursor.use {
            if (docCursor.moveToFirst()) docCount = docCursor.getInt(0)
        }

        val chunkCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CHUNKS", null)
        chunkCursor.use {
            if (chunkCursor.moveToFirst()) chunkCount = chunkCursor.getInt(0)
        }

        return Pair(docCount, chunkCount)
    }

    /**
     * 清空整库数据
     */
    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_CHUNKS, null, null)
            db.delete(TABLE_DOCUMENTS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
