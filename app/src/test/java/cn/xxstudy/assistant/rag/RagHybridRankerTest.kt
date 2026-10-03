package cn.xxstudy.assistant.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagHybridRankerTest {
    @Test
    fun exactHiveTitleSurvivesAStrictDenseThreshold() {
        val bonus = RagHybridRanker.lexicalBonus(
            query = "帮我介绍下 flutter 数据 Hive",
            docName = "数据库Hive.md",
            sectionTitle = "Hive 基础",
            content = "Flutter 中的 Hive 本地数据库"
        )

        assertTrue(bonus >= RagHybridRanker.LEXICAL_OVERRIDE_THRESHOLD)
        assertTrue(
            RagHybridRanker.shouldInclude(
                denseScore = 0.7789426f,
                denseThreshold = 0.80f,
                lexicalBonus = bonus
            )
        )
    }

    @Test
    fun unrelatedCandidateStillRespectsTheDenseThreshold() {
        val bonus = RagHybridRanker.lexicalBonus(
            query = "帮我介绍下 flutter 数据 Hive",
            docName = "Android 权限.md",
            sectionTitle = "SAF",
            content = "文件夹授权与持久化权限"
        )

        assertFalse(
            RagHybridRanker.shouldInclude(
                denseScore = 0.79f,
                denseThreshold = 0.80f,
                lexicalBonus = bonus
            )
        )
    }
}
