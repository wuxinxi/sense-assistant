package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.rag.db.KnowledgeSchemaMigration
import org.junit.Assert.*
import org.junit.Test

class KnowledgeSchemaMigrationTest {
    @Test fun v1PreservesSourceAndOnlyInvalidatesIncompatibleDenseVectors() {
        val sql = KnowledgeSchemaMigration.statements(1, 3)
        assertEquals(2, sql.size)
        assertTrue(sql.first().startsWith("ALTER TABLE documents ADD COLUMN size_bytes"))
        assertEquals("UPDATE chunks SET embedding = X''", sql.last())
        assertFalse(sql.any { it.contains("DROP") || it.contains("DELETE") })
    }
    @Test fun v2ToV3DoesNotRewriteExistingData() {
        assertTrue(KnowledgeSchemaMigration.statements(2, 3).isEmpty())
    }
    @Test fun unknownFutureMigrationFailsInsteadOfDroppingTables() {
        try { KnowledgeSchemaMigration.statements(3, 4); fail("unknown schema must fail closed") }
        catch (_: IllegalArgumentException) { }
    }
}
