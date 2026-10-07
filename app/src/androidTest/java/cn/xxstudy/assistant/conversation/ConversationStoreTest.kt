package cn.xxstudy.assistant.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.xxstudy.assistant.ui.screens.submitChatInput

/** Uses a uniquely named test fixture database, never the user's ledger. */
@RunWith(AndroidJUnit4::class)
class ConversationStoreTest {
    // Instrumentation executes under the target UID, not the separately installed test APK UID.
    // Use its writable context but only randomly named fixtures, never conversation.db/shared().
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val name = "conversation-fixture-${UUID.randomUUID()}.db"
    private lateinit var store: ConversationStore
    @Before fun setUp() { store = ConversationStore(context, name) }
    @After fun tearDown() { store.close(); context.deleteDatabase(name) }

    @Test fun finalAnswerSurvivesReopen() {
        val ticket = store.begin("one", 1, 2, "介绍Hive")
        assertTrue(store.complete(ticket, "Box [1]", AnswerKind.SOURCE_BACKED))
        store.close(); store = ConversationStore(context, name)
        val turn = store.restore().single()
        assertEquals("Box [1]", turn.answer)
        assertFalse(turn.memoryAnswer.contains("[1]"))
        assertEquals(TurnState.COMPLETED, turn.state)
    }

    @Test fun generalSupplementSourcesAndValidationSurviveDatabaseReopenAndUiRestore() {
        val source = cn.xxstudy.assistant.rag.RagSource(listOf(1), "数据库Hive.md", "Flutter使用", "原文 Hive.openBox('notes')")
        val display = ConversationDisplaySnapshot(listOf(source),
            "混合检索 · 3 个命中 · 64 ms · 摘要检查：资料说明缺少引用", "首字: 1914 ms")
        val ticket = store.begin("hive-display", 1, 2, "帮我介绍下 hive 的使用")
        assertTrue(store.complete(ticket, "通用补充\n未得到可核对的资料说明。", AnswerKind.GENERAL, display))
        assertNull(store.history().single().displaySnapshot)
        store.close(); store = ConversationStore(context, name)
        val restored = store.restore().single()
        assertEquals(display, restored.displaySnapshot)
        val message = restored.toRestoredChatMessages().last()
        assertEquals(restored.answer, message.text)
        assertEquals(listOf(source), message.knowledgeSources)
        assertEquals(display.retrievalInfo, message.knowledgeRetrievalInfo)
        assertEquals(display.metrics, message.metrics)
        assertTrue(message.isHistoricalKnowledge)
        assertTrue(message.isReadOnlyContent)
        assertFalse(restored.memoryAnswer.contains("openBox"))
        assertFalse(restored.memoryAnswer.contains("摘要检查"))
    }

    @Test fun staleCompletionCannotResurrectSnapshotAfterClear() {
        val ticket = store.begin("stale-display", 1, 2, "Hive")
        store.clear()
        val display = ConversationDisplaySnapshot(listOf(
            cn.xxstudy.assistant.rag.RagSource(listOf(1), "Hive.md", "使用", "旧原文")))
        assertFalse(store.complete(ticket, "旧正文", AnswerKind.SOURCE_BACKED, display))
        store.close(); store = ConversationStore(context, name)
        assertTrue(store.restore().isEmpty())
    }

    @Test fun versionTwoUpgradePreservesExistingAnswerAndDoesNotInventSnapshot() {
        store.writableDatabase
        store.close()
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL("DROP TABLE conversation_turns")
        legacy.execSQL("""CREATE TABLE conversation_turns (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL UNIQUE,
            epoch INTEGER NOT NULL, user_id INTEGER NOT NULL, answer_id INTEGER NOT NULL,
            question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', memory_answer TEXT NOT NULL DEFAULT '',
            kind TEXT NOT NULL DEFAULT 'GENERAL', state TEXT NOT NULL DEFAULT 'PENDING',
            retrieval_topic TEXT NOT NULL DEFAULT '', source_required INTEGER NOT NULL DEFAULT 0)""")
        legacy.execSQL("INSERT INTO conversation_turns(request_id,epoch,user_id,answer_id,question,answer,memory_answer,kind,state,retrieval_topic,source_required) VALUES('legacy',0,1,2,'介绍Hive','旧正文','旧正文','GENERAL','COMPLETED','Hive',1)")
        legacy.version = 2
        legacy.close()
        store = ConversationStore(context, name)
        val restored = store.restore().single()
        assertEquals("旧正文", restored.answer)
        assertEquals("Hive", restored.retrievalTopic)
        assertTrue(restored.sourceRequired)
        assertNull(restored.displaySnapshot)
        assertEquals(ConversationSchemaMigration.VERSION, store.readableDatabase.version)
    }

    @Test fun recapSurvivesReopenWithoutReplacingTopicsOrRepeatingQuotedFactsInMemory() {
        val first = store.begin("hive", 1, 2, "介绍 Hive")
        store.setRetrievalScope(first, "Hive", true)
        store.complete(first, "资料说明", AnswerKind.SOURCE_BACKED)
        val second = store.begin("zodiac", 3, 4, "你是什么星座")
        store.complete(second, "没有星座", AnswerKind.GENERAL)
        val ticket = store.begin("recap", 5, 6, "刚刚我们聊了什么？")
        val recap = ConversationRecap.build("刚刚我们聊了什么？", store.history(), "recap")
        assertEquals(listOf("hive", "zodiac"), recap.entries.map { it.requestId })
        assertTrue(store.complete(ticket, recap.text, AnswerKind.USER_RECAP))
        store.close(); store = ConversationStore(context, name)
        val recovered = store.restore()
        assertEquals(AnswerKind.USER_RECAP, recovered.last().kind)
        assertEquals(recap.text, recovered.last().answer)
        assertFalse(recovered.last().memoryAnswer.contains("Hive"))
        assertEquals("Hive", recovered.first().retrievalTopic)
        val again = ConversationRecap.build("我们刚刚聊了什么？", recovered)
        assertEquals(recap.entries, again.entries)
    }

    @Test fun coldRecapSubmissionPersistsOnlyActualQuestionsWithoutAModel() {
        val hive = store.begin("hive", 1, 2, "介绍 Hive")
        store.setRetrievalScope(hive, "Hive", false)
        store.complete(hive, "资料说明", AnswerKind.SOURCE_BACKED)
        val zodiac = store.begin("zodiac", 3, 4, "你是什么星座")
        store.complete(zodiac, "没有星座", AnswerKind.GENERAL)
        val accepted = submitChatInput("我们刚刚聊了什么？", true, false, true, { fail() }) { question ->
            val ticket = store.begin("cold-recap", 5, 6, question)
            val history = store.history()
            assertEquals(cn.xxstudy.assistant.rag.RagQueryRoute.RECAP,
                cn.xxstudy.assistant.rag.RagQueryResolver.resolve(question, history).route)
            val recap = ConversationRecap.build(question, history, ticket.requestId)
            assertEquals(listOf("介绍 Hive", "你是什么星座"), recap.entries.map { it.question })
            store.complete(ticket, recap.text, AnswerKind.USER_RECAP)
        }
        assertTrue(accepted)
        store.close(); store = ConversationStore(context, name)
        val recovered = store.restore().last()
        assertEquals(AnswerKind.USER_RECAP, recovered.kind)
        assertEquals("", recovered.retrievalTopic)
        assertFalse(recovered.sourceRequired)
        assertTrue(recovered.answer.contains("介绍 Hive"))
        assertTrue(recovered.answer.contains("你是什么星座"))
    }

    @Test fun resolvedTopicAndSourceOnlyPolicySurviveReopen() {
        val ticket = store.begin("one", 1, 2, "它怎么初始化")
        assertTrue(store.setRetrievalScope(ticket, "Flutter Hive", true))
        store.complete(ticket, "资料说明", AnswerKind.SOURCE_BACKED)
        store.close(); store = ConversationStore(context, name)
        val recovered = store.restore().single()
        assertEquals("Flutter Hive", recovered.retrievalTopic)
        assertTrue(recovered.sourceRequired)
    }

    @Test fun staleScopeUpdateCannotChangeCurrentRequest() {
        val old = store.begin("old", 1, 2, "旧问题")
        val current = store.begin("new", 3, 4, "新问题")
        assertFalse(store.setRetrievalScope(old, "Hive", true))
        assertTrue(store.setRetrievalScope(current, "SQLite", false))
        assertEquals("SQLite", store.history().last().retrievalTopic)
        assertFalse(store.history().last().sourceRequired)
    }

    @Test fun versionOneUpgradeKeepsHistoryAndEpochWithDefaultScope() {
        store.close()
        val legacy = object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE conversation_meta (id INTEGER PRIMARY KEY, epoch INTEGER NOT NULL)")
                db.execSQL("INSERT INTO conversation_meta VALUES (1, 7)")
                db.execSQL("""CREATE TABLE conversation_turns (sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT NOT NULL UNIQUE, epoch INTEGER NOT NULL, user_id INTEGER NOT NULL,
                    answer_id INTEGER NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '',
                    memory_answer TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'GENERAL', state TEXT NOT NULL DEFAULT 'PENDING')""")
                db.execSQL("INSERT INTO conversation_turns(request_id,epoch,user_id,answer_id,question,answer,memory_answer,kind,state) VALUES('legacy',7,1,2,'介绍Hive','旧答案','旧答案','GENERAL','COMPLETED')")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("fixture")
        }
        legacy.writableDatabase
        legacy.close()
        store = ConversationStore(context, name)
        val restored = store.restore().single()
        assertEquals("旧答案", restored.answer)
        assertEquals("", restored.retrievalTopic)
        assertFalse(restored.sourceRequired)
        assertEquals(7L, store.begin("new", 3, 4, "新问题").epoch)
    }

    @Test fun pendingRecoversAsInterruptedAndRejectsLateCompletion() {
        val ticket = store.begin("pending", 1, 2, "问题")
        store.close(); store = ConversationStore(context, name)
        assertEquals(TurnState.INTERRUPTED, store.restore().single().state)
        assertFalse(store.complete(ticket, "迟到答案", AnswerKind.GENERAL))
        assertTrue(ConversationMemory.completed(store.history()).isEmpty())
    }

    @Test fun supersededRequestCannotFinalize() {
        val old = store.begin("old", 1, 2, "旧问题")
        val current = store.begin("new", 3, 4, "新问题")
        assertFalse(store.complete(old, "旧答案", AnswerKind.GENERAL))
        assertTrue(store.complete(current, "新答案", AnswerKind.GENERAL))
        assertFalse(store.complete(current, "重复答案", AnswerKind.GENERAL))
        assertEquals("新答案", ConversationMemory.completed(store.history()).single().answer)
    }

    @Test fun clearInvalidatesTicketsAndDoesNotResurrectOldAnswers() {
        val old = store.begin("old", 1, 2, "旧问题")
        store.clear()
        val current = store.begin("new", 3, 4, "新问题")
        assertTrue(current.epoch > old.epoch)
        assertFalse(store.complete(old, "旧答案", AnswerKind.GENERAL))
        assertEquals(1, store.history().size)
    }

    @Test fun interruptionDoesNotDowngradeACompletedAnswer() {
        val ticket = store.begin("one", 1, 2, "问题")
        store.complete(ticket, "可靠答案", AnswerKind.GENERAL)
        store.interrupt(ticket)
        assertEquals(TurnState.COMPLETED, store.history().single().state)
    }

    @Test fun failedAndInterruptedRequestsAreNotFacts() {
        val ticket = store.begin("one", 1, 2, "问题")
        store.interrupt(ticket, failed = true)
        assertEquals(TurnState.FAILED, store.history().single().state)
        assertTrue(ConversationMemory.completed(store.history()).isEmpty())
    }

    @Test fun duplicateRequestRollsBackWithoutInterruptingExistingPendingTurn() {
        val ticket = store.begin("duplicate", 1, 2, "问题")
        try {
            store.begin("duplicate", 3, 4, "重复请求")
            fail("Expected uniqueness failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(TurnState.PENDING, store.history().single().state)
        assertTrue(store.complete(ticket, "答案", AnswerKind.GENERAL))
    }

    @Test fun retentionIsBoundedWithoutBreakingNewestExchange() {
        for (i in 1..105) {
            val ticket = store.begin("$i", i * 2, i * 2 + 1, "问题$i")
            store.complete(ticket, "答案$i", AnswerKind.GENERAL)
        }
        assertEquals(100, store.history().size)
        assertEquals("问题6", store.history().first().question)
        assertEquals("答案105", store.history().last().answer)
    }
}
