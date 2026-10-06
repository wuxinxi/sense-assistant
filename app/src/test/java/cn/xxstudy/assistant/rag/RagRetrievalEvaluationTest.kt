package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.rag.db.StoredChunk
import org.junit.Assert.*
import org.junit.Test

/** Real question wording, controlled corpus. This is not an on-phone model-quality benchmark. */
class RagRetrievalEvaluationTest {
    private val chunks = listOf(
        chunk(1, 1, "Flutter Hive.md", "概述", "Flutter Hive 是本地键值存储，用 Box 管理数据。"),
        chunk(2, 1, "Flutter Hive.md", "自定义对象", "Flutter Hive 保存自定义对象需要注册 TypeAdapter，并为字段定义编号。"),
        chunk(3, 1, "Flutter Hive.md", "初始化", "Flutter Hive 初始化与打开 Box：\n```dart\nawait Hive.initFlutter();\nfinal box = await Hive.openBox('settings');\n```"),
        chunk(4, 1, "Flutter Hive.md", "依赖", "Flutter Hive 依赖：hive_flutter，用于 Flutter 初始化。"),
        chunk(5, 2, "Apache Hive.md", "SQL", "Apache Hive 是数据仓库工具，使用 SQL 查询，不是 Flutter 的本地存储库。"),
        chunk(6, 3, "Flutter Riverpod.md", "状态管理", "Flutter Riverpod 提供状态管理。"),
        chunk(7, 4, "Android 权限.md", "SAF", "SAF 文件授权：申请持久化权限，保存文件夹访问授权。")
    )

    @Test fun keywordOnlyQuestionSetFindsTheRightTopicOrDeclines() {
        val index = RagSearchIndex(chunks)
        val cases = javaClass.getResourceAsStream("/rag/retrieval-questions.tsv")!!.bufferedReader().use { reader ->
            reader.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
        }
        val failures = mutableListOf<String>()
        for (case in cases) {
            val (expectedDoc, expectedSection, question) = case.split('\t')
            val result = index.search(RagQueryNormalizer.normalize(question), { null }, 3, .8f)
            val first = result.matches.firstOrNull()
            if (expectedDoc == "-") {
                if (first != null) failures += "$question: expected no hit, got ${first.docName}"
            } else if (first?.docName != expectedDoc || (expectedSection != "*" && first.sectionTitle != expectedSection)) {
                failures += "$question: expected $expectedDoc/$expectedSection, got ${first?.docName}/${first?.sectionTitle}"
            }
        }
        println("RAG keyword evaluation: ${cases.size - failures.size}/${cases.size} questions correct on controlled corpus")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun retrievedApiEvidenceKeepsItsReferenceAndOriginalCode() {
        val seeds = RagSearchIndex(chunks).search("Hive.openBox", { null }, 3, .8f).matches
        val prompt = RagPromptBuilder.build(seeds, "Hive.openBox 怎么用")
        assertTrue(prompt.contains("Hive.openBox"))
        val sources = RagSourcePresenter.sources(seeds)
        val answer = "使用 `Hive.openBox` 打开 Box。[1]"
        assertFalse(RagSourcePresenter.present(answer, seeds).isFallback)
        val linked = RagCitationLinks.linkify(answer, sources)
        assertTrue(linked.contains("[[1]](rag-source://reference/1)"))
        val source = sources[RagCitationLinks.sourceIndex("rag-source://reference/1", sources)!!]
        assertTrue(RagSourceReader.codeBlocks(source.content).single().code.contains("final box = await Hive.openBox('settings');"))
    }

    @Test fun unsupportedDependencyStillFallsBackAfterKeywordRetrieval() {
        val matches = RagSearchIndex(chunks).search("hive_flutter", { null }, 3, .8f).matches
        assertTrue(RagSourcePresenter.present("需要 `googleapis_hive` 依赖。[1]", matches).isFallback)
    }

    @Test fun topicInTheTitleBeatsIncidentalWordsInAnUnrelatedNote() {
        val result = RagSearchIndex(chunks).search("Flutter Hive", { null }, 8, .8f)
        assertTrue(result.matches.isNotEmpty())
        assertFalse("Apache Hive only mentions Flutter to explain the difference",
            result.matches.any { it.docName == "Apache Hive.md" })
    }

    private fun chunk(id: Long, docId: Long, doc: String, section: String, content: String) =
        StoredChunk(id, docId, doc, section, content, floatArrayOf(1f, 0f))
}
