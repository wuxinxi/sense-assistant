package cn.xxstudy.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cn.xxstudy.assistant.conversation.AnswerKind
import cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot
import cn.xxstudy.assistant.conversation.ConversationStore
import cn.xxstudy.assistant.conversation.toRestoredChatMessages
import cn.xxstudy.assistant.rag.RagSource
import cn.xxstudy.assistant.ui.components.ChatBubble
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real SQLite reopen → startup mapper → source card/reader. Never accesses the user's ledger. */
class RagHistoryDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceCardAndReaderRemainAvailableAfterDatabaseReopenForGeneralSupplement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val name = "rag-display-fixture-${UUID.randomUUID()}.db"
        var store = ConversationStore(context, name)
        try {
            val display = ConversationDisplaySnapshot(
                sources = listOf(RagSource(listOf(1), "数据库Hive.md", "Flutter使用", "回答当时保存的 Hive 原文")),
                retrievalInfo = "混合检索 · 3 个命中 · 64 ms · 摘要检查：资料说明缺少引用",
                metrics = "首字: 1914 ms")
            val ticket = store.begin("hive-display", 1, 2, "介绍 Hive")
            check(store.complete(ticket, "未得到可核对的资料说明。", AnswerKind.GENERAL, display))
            store.close()
            store = ConversationStore(context, name)
            val message = store.restore().single().toRestoredChatMessages().last()
            compose.setContent { MaterialTheme { ChatBubble(message) } }
            compose.onNodeWithText("历史原文快照 · 1 个章节").assertIsDisplayed()
            compose.onNodeWithText(requireNotNull(display.retrievalInfo)).assertIsDisplayed()
            compose.onNodeWithText(requireNotNull(display.metrics)).assertIsDisplayed()
            compose.onNodeWithText("查看原文").performClick()
            compose.onNodeWithText("历史原文快照").assertIsDisplayed()
            compose.onNodeWithText("回答当时保存的 Hive 原文").assertIsDisplayed()
            compose.onNodeWithText("回答当时保存的资料；打开 Obsidian 查看的是当前笔记，内容可能已变化。").assertIsDisplayed()
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
