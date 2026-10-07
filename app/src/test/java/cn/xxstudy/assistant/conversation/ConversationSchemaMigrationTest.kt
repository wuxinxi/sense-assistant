package cn.xxstudy.assistant.conversation

import org.junit.Assert.*
import org.junit.Test

class ConversationSchemaMigrationTest {
    @Test fun versionOneAddsScopeWithoutDroppingOrRewritingHistory() {
        val sql = ConversationSchemaMigration.statements(1, 2)
        assertEquals(2, sql.size)
        assertTrue(sql.all { it.startsWith("ALTER TABLE conversation_turns ADD COLUMN") })
        assertFalse(sql.any { Regex("DROP|DELETE|UPDATE|REPLACE", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }
    @Test(expected = IllegalArgumentException::class) fun unknownVersionDoesNotDestructivelyRecover() {
        ConversationSchemaMigration.statements(0, 2)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownFutureMigrationFailsClosed() {
        ConversationSchemaMigration.statements(3, 4)
    }
    @Test fun versionTwoAddsDisplaySnapshotWithoutRewritingHistory() {
        assertEquals(listOf("ALTER TABLE conversation_turns ADD COLUMN display_snapshot BLOB"),
            ConversationSchemaMigration.statements(2, 3))
    }
    @Test fun versionOneCanUpgradeDirectlyToLatestWithoutDeletingHistory() {
        val sql = ConversationSchemaMigration.statements(1, 3)
        assertEquals(3, sql.size)
        assertTrue(sql.all { it.startsWith("ALTER TABLE conversation_turns ADD COLUMN") })
    }
}
