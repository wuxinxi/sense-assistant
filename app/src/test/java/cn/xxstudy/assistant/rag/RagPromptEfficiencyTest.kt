package cn.xxstudy.assistant.rag

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPromptEfficiencyTest {
    @Test
    fun reducesFixedHivePromptWithoutLosingEssentialFacts() {
        val matches = listOf(
            KnowledgeMatch("Hive.md", "介绍", """
                [笔记: Hive > 介绍]
                Hive 是轻量级键值数据库，通过 Box 保存数据，适合本地设置和缓存。
                ```dart
                await Hive.initFlutter();
                final box = await Hive.openBox('settings');
                await box.put('theme', 'dark');
                final theme = box.get('theme');
                ```
            """.trimIndent(), .9f),
            KnowledgeMatch("Hive.md", "依赖", """
                [笔记: Hive > 依赖]
                Flutter 项目使用 hive 和 hive_flutter。
                ```yaml
                hive: ^2.2.3
                hive_flutter: ^1.1.0
                ```
            """.trimIndent(), .8f)
        )
        val before = javaClass.getResource("/rag/hive-prompt-before.txt")!!.readText().trimEnd()
        val after = RagPromptBuilder.build(matches, "帮我介绍 Flutter 数据库 Hive")
        assertTrue("before=${before.length}, after=${after.length}", after.length < before.length * .8)
        listOf("Hive.initFlutter()", "Hive.openBox", "hive_flutter: ^1.1.0", "轻量级键值数据库").forEach {
            assertTrue("missing source fact $it", after.contains(it))
        }
        println("RAG_PROMPT_FIXTURE before_chars=${before.length} after_chars=${after.length}")
        // Optional generated fixtures for the vocabulary-only device probe.
        System.getenv("RAG_PROMPT_FIXTURES_DIR")?.let { path ->
            val directory = File(path).also { it.mkdirs() }
            val prefix = "<|im_start|>system\n你是一个简洁的中文助手。<|im_end|>\n<|im_start|>user\n"
            val suffix = "\n\n我的问题是：帮我介绍 Flutter 数据库 Hive<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
            File(directory, "before.txt").writeText(prefix + before + suffix)
            File(directory, "after.txt").writeText(prefix + after + suffix)
        }
    }
}
