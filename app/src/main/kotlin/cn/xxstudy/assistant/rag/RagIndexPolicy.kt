package cn.xxstudy.assistant.rag

/** File metadata alone cannot detect a changed chunking algorithm. */
object RagIndexPolicy {
    fun canSkip(
        indexedRevision: Int,
        currentRevision: Int,
        recordedModified: Long?,
        recordedSize: Long?,
        modified: Long,
        size: Long
    ): Boolean = indexedRevision == currentRevision && modified > 0L && size > 0L &&
        recordedModified == modified && recordedSize == size
}
