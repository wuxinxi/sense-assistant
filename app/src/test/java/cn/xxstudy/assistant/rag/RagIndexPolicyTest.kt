package cn.xxstudy.assistant.rag

import org.junit.Assert.*
import org.junit.Test

class RagIndexPolicyTest {
    @Test
    fun unchangedFilesStillReindexAfterChunkerUpgrade() {
        assertFalse(RagIndexPolicy.canSkip(1, 2, 100L, 200L, 100L, 200L))
        assertTrue(RagIndexPolicy.canSkip(2, 2, 100L, 200L, 100L, 200L))
    }

    @Test
    fun changedNewAndUnreliableFilesAreNeverSkipped() {
        assertFalse(RagIndexPolicy.canSkip(2, 2, 100L, 200L, 101L, 200L))
        assertFalse(RagIndexPolicy.canSkip(2, 2, null, null, 100L, 200L))
        assertFalse(RagIndexPolicy.canSkip(2, 2, 0L, 0L, 0L, 0L))
    }
}
