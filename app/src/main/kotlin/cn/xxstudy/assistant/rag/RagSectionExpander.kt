package cn.xxstudy.assistant.rag

data class RagSectionChunk(
    val id: Long,
    val docId: Long,
    val docName: String,
    val sectionTitle: String,
    val content: String
)

/** Expands a semantic seed to the complete source section, preserving file order. */
object RagSectionExpander {
    fun expand(
        seeds: List<KnowledgeMatch>,
        allChunks: List<RagSectionChunk>,
        maxChars: Int
    ): List<RagSectionChunk> {
        if (seeds.isEmpty() || maxChars <= 0) return emptyList()

        val selectedSections = seeds.filter { it.docId != null }
            .map { it.docId to it.sectionTitle }
            .toSet()
        val legacySections = seeds.filter { it.docId == null }.map { it.docName to it.sectionTitle }.toSet()
        val result = mutableListOf<RagSectionChunk>()
        var usedChars = 0

        for (chunk in allChunks.sortedBy { it.id }) {
            if ((chunk.docId to chunk.sectionTitle) !in selectedSections &&
                (chunk.docName to chunk.sectionTitle) !in legacySections) continue
            if (usedChars + chunk.content.length > maxChars) {
                val remaining = maxChars - usedChars
                if (remaining > 0) {
                    result += chunk.copy(
                        content = chunk.content.take(remaining).trimEnd() +
                            "\n\n> 原文较长，已显示到本地知识库输出上限。"
                    )
                }
                break
            }
            result += chunk
            usedChars += chunk.content.length
        }
        return result
    }
}
