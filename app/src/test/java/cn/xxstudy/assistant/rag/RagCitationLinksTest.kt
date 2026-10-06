package cn.xxstudy.assistant.rag

import org.junit.Assert.*
import org.junit.Test

class RagCitationLinksTest {
    private val sources = listOf(
        RagSource(listOf(1, 2), "Hive.md", "使用", "资料甲"),
        RagSource(listOf(3), "Box.md", "概念", "资料乙")
    )

    @Test fun numberedReferencesBecomeLocalLinksWithoutChangingSourceIds() {
        assertEquals("初始化。[[1]](rag-source://reference/1) 存储。[[3]](rag-source://reference/3)",
            RagCitationLinks.linkify("初始化。[1] 存储。[3]", sources))
        assertEquals(0, RagCitationLinks.sourceIndex("rag-source://reference/2", sources))
        assertEquals(1, RagCitationLinks.sourceIndex("rag-source://reference/3", sources))
    }

    @Test fun unknownIdsAndMalformedUrisAreNotClickable() {
        assertEquals("未知。[9] [999999999999999999999]", RagCitationLinks.linkify("未知。[9] [999999999999999999999]", sources))
        assertNull(RagCitationLinks.sourceIndex("rag-source://reference/9", sources))
        assertNull(RagCitationLinks.sourceIndex("https://reference/1", sources))
        assertNull(RagCitationLinks.sourceIndex("rag-source://reference/1?other=3", sources))
    }

    @Test fun codeAndExistingMarkdownLinksAreNotRewritten() {
        val text = "`items[1]` [1](https://example.com)\n```dart\nitems[2];\n```\n正文。[2]"
        assertEquals("`items[1]` [1](https://example.com)\n```dart\nitems[2];\n```\n正文。[[2]](rag-source://reference/2)",
            RagCitationLinks.linkify(text, sources))
    }

    @Test fun escapesImagesReferenceDefinitionsAndTildeFencesRemainIntact() {
        val text = "\\[1] ![1](image.png) [1][ref]\n[1]: https://example.com\n~~~\n[2]\n~~~\n引用。[3]"
        assertEquals(text.replace("引用。[3]", "引用。[[3]](rag-source://reference/3)"), RagCitationLinks.linkify(text, sources))
    }

    @Test fun adjacentCitationsAndCrLfKeepTheirOriginalFormatting() {
        val text = "**正文**。[1][3]\r\n下一行。[2]\r\n"
        assertEquals("**正文**。[[1]](rag-source://reference/1)[[3]](rag-source://reference/3)\r\n下一行。[[2]](rag-source://reference/2)\r\n", RagCitationLinks.linkify(text, sources))
    }

    @Test fun quotedFencedAndIndentedCodeAreNeverLinked() {
        val text = "> ```dart\n> items[1];\n> ```\n\n    items[2];\n\n正文。[3]"
        assertEquals(text.replace("正文。[3]", "正文。[[3]](rag-source://reference/3)"), RagCitationLinks.linkify(text, sources))
    }

    @Test fun alreadyLinkedAnswersAreIdempotentAndDoNotMutateRawSources() {
        val text = "正文。[1]"
        val once = RagCitationLinks.linkify(text, sources)
        assertEquals(once, RagCitationLinks.linkify(once, sources))
        assertEquals("资料甲", sources[0].content)
    }

    @Test fun renderedLinkStillDisplaysBracketedReferenceNumber() {
        val labels = mutableListOf<String>()
        org.commonmark.parser.Parser.builder().build().parse(RagCitationLinks.linkify("正文。[1]", sources))
            .accept(object : org.commonmark.node.AbstractVisitor() {
                override fun visit(node: org.commonmark.node.Link) {
                    assertEquals("rag-source://reference/1", node.destination)
                    labels += (node.firstChild as org.commonmark.node.Text).literal
                }
            })
        assertEquals(listOf("[1]"), labels)
    }
}
