package cn.xxstudy.assistant.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGroundingGuardTest {
    private val matches = listOf(
        KnowledgeMatch(
            docName = "Fluro.md",
            sectionTitle = "依赖",
            content = "[笔记: Fluro > 依赖]\n```dart\nfluro: ^2.0.3\n```",
            score = 0.72f
        ),
        KnowledgeMatch(
            docName = "Fluro.md",
            sectionTitle = "Fluro使用步骤",
            content = "[笔记: Fluro > Fluro使用步骤]\n- 构建 `FluroRouter` 路由实例，单例\n  static final appRouter = FluroRouter();",
            score = 0.69f
        )
    )

    @Test
    fun replacesUncitedGenericAnswerWithKnowledgeBaseExcerpts() {
        val answer = RagGroundingGuard.ensureGrounded(
            generated = "Fluro 是一个流行的 Flutter 集成模块，可以帮助开发者管理应用。",
            matches = matches
        )

        assertTrue(answer.contains("Fluro.md · 依赖"))
        assertTrue(answer.contains("fluro: ^2.0.3"))
        assertTrue(answer.contains("static final appRouter = FluroRouter();"))
        assertTrue(answer.contains("根据本地知识库，找到以下原文"))
    }

    @Test
    fun keepsAnswerThatCitesSourceAndUsesSourceFact() {
        val generated = "根据 Fluro.md，依赖版本是 `fluro: ^2.0.3`。"

        assertEquals(generated, RagGroundingGuard.ensureGrounded(generated, matches))
    }

    @Test
    fun removesSlidingWindowOverlapWhenJoiningASection() {
        val overlapping = listOf(
            KnowledgeMatch("Fluro.md", "步骤", "第一段内容\n共同的重叠内容", 0.8f),
            KnowledgeMatch("Fluro.md", "步骤", "共同的重叠内容\n第二段内容", 0.79f)
        )

        val answer = RagGroundingGuard.buildExtractiveFallback(overlapping)

        assertEquals(1, Regex("共同的重叠内容").findAll(answer).count())
        assertTrue(answer.contains("第一段内容\n共同的重叠内容\n第二段内容"))
    }

    @Test
    fun rejectsAnswerWithOnlyOneWeakSourceAnchor() {
        val generated = "根据 Fluro.md，这是一段通用说明。"

        assertTrue(RagGroundingGuard.ensureGrounded(generated, matches).contains("根据本地知识库，找到以下原文"))
    }
}
