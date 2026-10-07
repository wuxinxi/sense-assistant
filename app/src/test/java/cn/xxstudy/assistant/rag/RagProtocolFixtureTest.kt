package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.conversation.*
import cn.xxstudy.assistant.repository.ConversationPromptBuilder
import org.junit.Assert.*
import org.junit.Test

/** In-memory protocol contrasts using production builders, with no fixture-export side effects. */
class RagProtocolFixtureTest {
    @Test fun buildsSingleVariableProtocolContrastsUsingProductionBuilders() {
        val evidence = listOf(
            KnowledgeMatch("synthetic-hive.md", "介绍", "Hive 是轻量级键值数据库，通过 Box 保存数据，适合本地设置和缓存。", .9f),
            KnowledgeMatch("synthetic-hive.md", "初始化", "Flutter 项目使用 hive 和 hive_flutter。先调用 Hive.initFlutter()，再通过 Hive.openBox() 打开 Box。", .8f)
        )
        val history = (1..3).map { ConversationTurn("fixture-$it", it * 2, it * 2 + 1,
            "介绍 Hive", "合成的失败回答", ConversationMemory.project("", AnswerKind.FALLBACK),
            AnswerKind.FALLBACK, TurnState.COMPLETED, "Hive") }
        fun build(policy: RagAnswerPolicy, turns: List<ConversationTurn>, labels: Boolean): String {
            var references = RagPromptBuilder.build(evidence, "帮我介绍一下 Hive", policy)
            if (labels) references = references.replace(Regex("(<reference id=\"(\\d+)\"[^\\n]*>\\n)")) {
                it.value + "引用编号：[${it.groupValues[2]}]\n"
            }
            return ConversationPromptBuilder.build("你是端侧智能助手。请提供准确回答。", "帮我介绍一下 Hive",
                references, true, true, turns, policy.systemInstruction().substringBefore("\n输出格式示例"))
        }
        val fixtures = linkedMapOf(
            "fusion" to build(RagAnswerPolicy.FUSION, emptyList(), false),
            "fusion-history" to build(RagAnswerPolicy.FUSION, history, false),
            "source-only" to build(RagAnswerPolicy.SOURCE_ONLY, emptyList(), false),
            "fusion-labels" to build(RagAnswerPolicy.FUSION, emptyList(), true),
            "fusion-history-labels" to build(RagAnswerPolicy.FUSION, history, true)
        )
        val example = "\n输出格式示例（句子只是格式占位，不是资料事实，禁止照抄）：\n【资料说明】\n- 从当前资料提炼一个要点。[1]\n【通用补充】\n- 可选的概念或建议。\n实际回答替换示例句子；资料段引用当前资料编号；无需通用补充时省略整个通用段。"
        for (name in listOf("fusion", "fusion-history")) {
            val original = fixtures.getValue(name)
            val rules = RagAnswerPolicy.FUSION.systemInstruction().substringBefore("\n输出格式示例")
            fixtures["$name-template"] = original.replace(rules, rules + example)
            assertEquals(original, fixtures.getValue("$name-template").replace(example, ""))
            assertTrue(fixtures.getValue("$name-template").contains(RagAnswerPolicy.FUSION.systemInstruction()))
        }
        assertEquals(fixtures.getValue("fusion"), fixtures.getValue("fusion-labels")
            .replace("引用编号：[1]\n", "").replace("引用编号：[2]\n", ""))
        assertEquals(fixtures.getValue("fusion-history"), fixtures.getValue("fusion-history-labels")
            .replace("引用编号：[1]\n", "").replace("引用编号：[2]\n", ""))
        fixtures.values.forEach { assertTrue(it.contains("Hive 是轻量级键值数据库")) }
    }
}
