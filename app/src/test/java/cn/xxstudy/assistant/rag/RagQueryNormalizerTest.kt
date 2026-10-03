package cn.xxstudy.assistant.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagQueryNormalizerTest {
    @Test
    fun removesKnowledgeBaseCommandWordsFromNamedTopicQuery() {
        assertEquals("Fluro", RagQueryNormalizer.normalize("通过知识库查询 Fluro"))
        assertEquals("Fluro", RagQueryNormalizer.normalize("请帮我从本地知识库中查找：Fluro"))
    }

    @Test
    fun keepsTheActualQuestionIntact() {
        assertEquals(
            "Android Compose 状态管理",
            RagQueryNormalizer.normalize("在知识库里搜索 Android Compose 状态管理")
        )
        assertEquals("怎么配置 Fluro 路由？", RagQueryNormalizer.normalize("怎么配置 Fluro 路由？"))
    }

    @Test
    fun identifiesExplicitKnowledgeBaseLookups() {
        assertTrue(RagQueryNormalizer.isExplicitKnowledgeLookup("通过知识库查询 Fluro"))
        assertTrue(RagQueryNormalizer.isExplicitKnowledgeLookup("在知识库里搜索 Compose"))
        assertFalse(RagQueryNormalizer.isExplicitKnowledgeLookup("怎么配置 Fluro 路由？"))
    }

    @Test
    fun identifiesKnowledgeBaseStatusQuestionsWithoutTreatingTopicQueriesAsStatus() {
        assertTrue(RagQueryNormalizer.isKnowledgeBaseStatusQuery("你了解我的知识库吗"))
        assertTrue(RagQueryNormalizer.isKnowledgeBaseStatusQuery("我的知识库里有什么？"))
        assertTrue(RagQueryNormalizer.isKnowledgeBaseStatusQuery("知识库同步了吗？"))

        assertFalse(RagQueryNormalizer.isKnowledgeBaseStatusQuery("知识库里 Fluro 怎么配置？"))
        assertFalse(RagQueryNormalizer.isKnowledgeBaseStatusQuery("通过知识库查询 Compose"))
    }
}
