package cn.xxstudy.assistant.rag

import org.junit.Assert.*
import org.junit.Test

class RagSourcePresenterTest {
    @Test
    fun sameNamedDocumentsRemainSeparateReferences() {
        val seeds = listOf(
            KnowledgeMatch("Hive.md", "使用", "资料甲", .9f, 1),
            KnowledgeMatch("Hive.md", "使用", "资料乙", .8f, 2)
        )
        val sources = RagSourcePresenter.sources(seeds)
        assertEquals(2, sources.size)
        assertEquals("资料甲", sources[0].content)
        assertEquals("资料乙", sources[1].content)
    }

    @Test
    fun groupsSameSectionButPreservesPromptCitationIdsAndExpandedCode() {
        val seeds = listOf(
            KnowledgeMatch("Hive.md", "使用", "第一段说明\n共同的重叠内容", .9f),
            KnowledgeMatch("Hive.md", "使用", "共同的重叠内容\n第二段说明", .8f),
            KnowledgeMatch("Box.md", "概念", "Box 用于存储键值。", .8f)
        )
        val expanded = seeds.take(2) + KnowledgeMatch("Hive.md", "使用", "```dart\nHive.openBox('settings');\n```", .7f)
        val sources = RagSourcePresenter.sources(seeds, expanded)
        assertEquals(listOf(1, 2), sources[0].referenceIds)
        assertEquals(listOf(3), sources[1].referenceIds)
        assertEquals(1, Regex("共同的重叠内容").findAll(sources[0].content).count())
        assertTrue(sources[0].content.contains("Hive.openBox('settings');"))
        assertTrue(sources[1].content.contains("Box 用于存储键值。"))
    }

    @Test
    fun failedSummaryDoesNotCopyOrSpeakThousandsOfSourceCharacters() {
        val matches = listOf(KnowledgeMatch("Hive.md", "原文", "原始资料".repeat(2000), .8f))
        val result = RagSourcePresenter.present("需要 googleapis_hive 依赖", matches)
        assertTrue(result.isFallback)
        assertTrue(result.text.length < 100)
        assertEquals(matches.single().content, RagSourcePresenter.sources(matches).single().content)
    }

    @Test
    fun keepsVerifiedBriefAnswerAndDoesNotChangeNonRagAnswer() {
        val match = KnowledgeMatch("Hive.md", "依赖", "hive_flutter: ^1.1.0", .9f)
        val answer = "依赖为 `hive_flutter: ^1.1.0`。[1]"
        assertEquals(RagPresentedAnswer(answer, false), RagSourcePresenter.present(answer, listOf(match)))
        assertEquals(RagPresentedAnswer("你好", false), RagSourcePresenter.present("你好", emptyList()))
    }
}
