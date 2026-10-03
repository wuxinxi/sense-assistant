package cn.xxstudy.assistant.rag

import org.junit.Assert.assertTrue
import org.junit.Test

class RagStatusResponseTest {
    @Test
    fun reportsTheRealLocalIndexInsteadOfClaimingItCannotAccessTheKnowledgeBase() {
        val answer = RagStatusResponse.build(
            enabled = true,
            vaultName = "tangren-note",
            docCount = 1_418,
            chunkCount = 18_679
        )

        assertTrue(answer.contains("tangren-note"))
        assertTrue(answer.contains("1418"))
        assertTrue(answer.contains("18679"))
        assertTrue(answer.contains("已连接"))
    }

    @Test
    fun explainsWhenTheIndexHasNotBeenBuiltYet() {
        val answer = RagStatusResponse.build(
            enabled = true,
            vaultName = "notes",
            docCount = 0,
            chunkCount = 0
        )

        assertTrue(answer.contains("尚未完成索引"))
    }
}
