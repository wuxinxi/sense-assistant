package cn.xxstudy.assistant.rag

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Internal citations are resolved only against this answer's source snapshot. */
object RagCitationLinks {
    private val linkPattern = Regex("rag-source://reference/([1-9][0-9]*)")
    private val citationPattern = Regex("\\[([1-9][0-9]*)]")
    private val parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

    fun sourceIndex(url: String, sources: List<RagSource>): Int? {
        val id = linkPattern.matchEntire(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return sources.indexOfFirst { id in it.referenceIds }.takeIf { it >= 0 }
    }

    fun linkify(text: String, sources: List<RagSource>): String {
        if (sources.isEmpty() || !citationPattern.containsMatchIn(text)) return text
        val validIds = sources.flatMap { it.referenceIds }.toSet()
        val lineStarts = listOf(0) + Regex("\r\n|\r|\n").findAll(text).map { it.range.last + 1 }.toList()
        val protected = BooleanArray(text.length)
        // Parse actual Markdown structure, not a fence regex: quoted/nested code,
        // inline code, images, and existing links must retain their original bytes.
        parser.parse(text).accept(object : AbstractVisitor() {
            private fun protect(node: Node) {
                for (span in node.sourceSpans) {
                    val start = lineStarts[span.lineIndex] + span.columnIndex
                    for (i in start until minOf(start + span.length, text.length)) protected[i] = true
                }
            }
            override fun visit(node: Code) = protect(node)
            override fun visit(node: FencedCodeBlock) = protect(node)
            override fun visit(node: IndentedCodeBlock) = protect(node)
            override fun visit(node: HtmlBlock) = protect(node)
            override fun visit(node: HtmlInline) = protect(node)
            override fun visit(node: Link) = protect(node)
            override fun visit(node: Image) = protect(node)
            override fun visit(node: LinkReferenceDefinition) = protect(node)
        })
        return buildString {
            var offset = 0
            for (match in citationPattern.findAll(text)) {
                val start = match.range.first
                val end = match.range.last + 1
                val id = match.groupValues[1].toIntOrNull()
                var slashes = 0
                var before = start - 1
                while (before >= 0 && text[before--] == '\\') slashes++
                val next = text.getOrNull(end)
                val isReferenceLabel = next == '[' &&
                    Regex("\\[[^]\\n]+]").find(text, end)?.takeIf { it.range.first == end }
                        ?.value?.removeSurrounding("[", "]")?.toIntOrNull() == null
                if (id !in validIds || match.range.any { protected[it] } || slashes % 2 == 1 ||
                    text.getOrNull(start - 1) == '!' || next == '(' || next == ':' || isReferenceLabel) continue
                append(text, offset, start)
                // Nested label brackets remain visible as [1], not an ambiguous bare digit.
                append("[[$id]](rag-source://reference/$id)")
                offset = end
            }
            append(text, offset, text.length)
        }
    }
}
