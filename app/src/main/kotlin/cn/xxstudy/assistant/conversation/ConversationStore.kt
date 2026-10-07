package cn.xxstudy.assistant.conversation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local single-conversation ledger. All methods must run off the UI thread. */
class ConversationStore(context: Context, databaseName: String = "conversation.db") :
    SQLiteOpenHelper(context.applicationContext ?: context, databaseName, null, ConversationSchemaMigration.VERSION) {
    companion object {
        private val lock = Any()
        private const val RETAINED_TURNS = 100
        private var instance: ConversationStore? = null
        fun shared(context: Context): ConversationStore = synchronized(lock) {
            instance ?: ConversationStore(context).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversation_meta (id INTEGER PRIMARY KEY CHECK(id=1), epoch INTEGER NOT NULL)")
        db.execSQL("INSERT INTO conversation_meta VALUES (1, 0)")
        db.execSQL("""CREATE TABLE conversation_turns (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL UNIQUE,
            epoch INTEGER NOT NULL, user_id INTEGER NOT NULL, answer_id INTEGER NOT NULL,
            question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', memory_answer TEXT NOT NULL DEFAULT '',
            kind TEXT NOT NULL DEFAULT 'GENERAL', state TEXT NOT NULL DEFAULT 'PENDING',
            retrieval_topic TEXT NOT NULL DEFAULT '', source_required INTEGER NOT NULL DEFAULT 0,
            display_snapshot BLOB)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ConversationSchemaMigration.statements(oldVersion, newVersion).forEach(db::execSQL)
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    fun restore(): List<ConversationTurn> = transaction { db ->
        // A process/view-model restart cannot resume an in-flight native request.
        db.execSQL("UPDATE conversation_turns SET state='INTERRUPTED' WHERE state='PENDING'")
        read(db)
    }

    /** Inference/recap history excludes display BLOBs: no raw excerpts or extra decoding per turn. */
    fun history(): List<ConversationTurn> = synchronized(lock) { read(readableDatabase, includeDisplay = false) }

    fun begin(requestId: String, userId: Int, answerId: Int, question: String): TurnTicket = transaction { db ->
        val epoch = db.rawQuery("SELECT epoch FROM conversation_meta WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
        // Supersession is atomic: a late completion from the old request cannot commit.
        db.execSQL("UPDATE conversation_turns SET state='INTERRUPTED' WHERE state='PENDING'")
        db.insertOrThrow("conversation_turns", null, ContentValues().apply {
            put("request_id", requestId); put("epoch", epoch); put("user_id", userId)
            put("answer_id", answerId); put("question", question)
        })
        db.execSQL("DELETE FROM conversation_turns WHERE sequence NOT IN (SELECT sequence FROM conversation_turns ORDER BY sequence DESC LIMIT $RETAINED_TURNS)")
        TurnTicket(requestId, epoch)
    }

    fun complete(ticket: TurnTicket, answer: String, kind: AnswerKind,
                 displaySnapshot: ConversationDisplaySnapshot? = null): Boolean = transaction { db ->
        db.update("conversation_turns", ContentValues().apply {
            put("answer", answer); put("memory_answer", ConversationMemory.project(answer, kind))
            put("kind", kind.name); put("state", TurnState.COMPLETED.name)
            if (displaySnapshot == null) putNull("display_snapshot")
            else put("display_snapshot", ConversationDisplayCodec.encode(displaySnapshot))
        }, "request_id=? AND epoch=? AND state='PENDING' AND epoch=(SELECT epoch FROM conversation_meta WHERE id=1)",
            arrayOf(ticket.requestId, ticket.epoch.toString())) == 1
    }

    fun setRetrievalScope(ticket: TurnTicket, topic: String, sourceRequired: Boolean): Boolean = transaction { db ->
        db.update("conversation_turns", ContentValues().apply {
            put("retrieval_topic", topic); put("source_required", if (sourceRequired) 1 else 0)
        }, "request_id=? AND epoch=? AND state='PENDING' AND epoch=(SELECT epoch FROM conversation_meta WHERE id=1)",
            arrayOf(ticket.requestId, ticket.epoch.toString())) == 1
    }

    fun interrupt(ticket: TurnTicket, failed: Boolean = false) = transaction { db ->
        db.update("conversation_turns", ContentValues().apply {
            put("state", if (failed) TurnState.FAILED.name else TurnState.INTERRUPTED.name)
        }, "request_id=? AND epoch=? AND state='PENDING'", arrayOf(ticket.requestId, ticket.epoch.toString()))
    }

    fun clear() = transaction { db ->
        db.execSQL("UPDATE conversation_meta SET epoch=epoch+1 WHERE id=1")
        db.delete("conversation_turns", null, null)
    }

    private fun read(db: SQLiteDatabase, includeDisplay: Boolean = true): List<ConversationTurn> = db.rawQuery(
        "SELECT request_id,user_id,answer_id,question,answer,memory_answer,kind,state,retrieval_topic,source_required," +
            (if (includeDisplay) "display_snapshot" else "NULL") + " FROM conversation_turns ORDER BY sequence", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(ConversationTurn(
            cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getString(3),
            cursor.getString(4), cursor.getString(5), AnswerKind.valueOf(cursor.getString(6)), TurnState.valueOf(cursor.getString(7)),
            cursor.getString(8), cursor.getInt(9) != 0,
            if (cursor.isNull(10)) null else ConversationDisplayCodec.decode(cursor.getBlob(10))
        ))
    } }
}
