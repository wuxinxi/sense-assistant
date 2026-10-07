package cn.xxstudy.assistant.conversation

import cn.xxstudy.assistant.rag.RagSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Final display only. Never replay these source excerpts or diagnostics as model history. */
data class ConversationDisplaySnapshot(
    val sources: List<RagSource> = emptyList(),
    val retrievalInfo: String? = null,
    val metrics: String? = null,
    val isKnowledgeExcerpt: Boolean = false
)

/** Versioned, bounded local BLOB; corrupt/unknown data fails closed rather than inventing sources. */
object ConversationDisplayCodec {
    private const val VERSION = 1
    private const val MAX_BYTES = 1_048_576
    private const val MAX_SOURCES = 128
    private const val MAX_REFERENCES = 128

    fun encode(snapshot: ConversationDisplaySnapshot): ByteArray {
        require(snapshot.sources.size <= MAX_SOURCES)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeBoolean(snapshot.isKnowledgeExcerpt)
            out.writeText(snapshot.retrievalInfo)
            out.writeText(snapshot.metrics)
            out.writeInt(snapshot.sources.size)
            snapshot.sources.forEach { source ->
                require(source.referenceIds.size <= MAX_REFERENCES && source.referenceIds.all { it > 0 })
                out.writeInt(source.referenceIds.size)
                source.referenceIds.forEach(out::writeInt)
                out.writeText(source.docName)
                out.writeText(source.sectionTitle)
                out.writeText(source.content)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): ConversationDisplaySnapshot {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported display snapshot version" }
            val excerpt = input.readBoolean()
            val retrieval = input.readText()
            val metrics = input.readText()
            val sources = List(input.readCount(MAX_SOURCES)) {
                val refs = List(input.readCount(MAX_REFERENCES)) { input.readInt().also { require(it > 0) } }
                RagSource(refs, requireNotNull(input.readText()), requireNotNull(input.readText()), requireNotNull(input.readText()))
            }
            require(input.available() == 0) { "Trailing snapshot data" }
            ConversationDisplaySnapshot(sources, retrieval, metrics, excerpt)
        }
    }

    private fun DataOutputStream.writeText(value: String?) {
        if (value == null) { writeInt(-1); return }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String? {
        val size = readInt()
        if (size == -1) return null
        require(size in 0..MAX_BYTES && size <= available())
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readCount(max: Int): Int = readInt().also { require(it in 0..max) }
}
