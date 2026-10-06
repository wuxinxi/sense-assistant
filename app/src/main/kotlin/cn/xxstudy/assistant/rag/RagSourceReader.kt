package cn.xxstudy.assistant.rag

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.parser.Parser

data class RagCodeBlock(val language: String, val code: String)

/** Copy source code verbatim from parsed blocks; never ask the model to regenerate it. */
object RagSourceReader {
    private val parser = Parser.builder().build()

    fun codeBlocks(content: String): List<RagCodeBlock> = buildList {
        parser.parse(content).accept(object : AbstractVisitor() {
            override fun visit(node: FencedCodeBlock) {
                add(RagCodeBlock(node.info.orEmpty().substringBefore(' '), node.literal))
            }
            override fun visit(node: IndentedCodeBlock) {
                add(RagCodeBlock("", node.literal))
            }
        })
    }
}
