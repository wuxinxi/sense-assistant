package cn.xxstudy.assistant.rag

/** Deterministic answers for knowledge-base state and retrieval failures. */
object RagStatusResponse {
    fun build(
        enabled: Boolean,
        vaultName: String,
        docCount: Int,
        chunkCount: Int
    ): String {
        if (!enabled) {
            return "本地知识库检索当前未启用。请在设置中开启 Obsidian 知识库检索并同步笔记。"
        }

        val displayName = vaultName.ifBlank { "本地知识库" }
        if (docCount <= 0 || chunkCount <= 0) {
            return "我已连接本地知识库「$displayName」，但尚未完成索引。" +
                "请先在设置中点击“立即同步”。"
        }

        return "可以。我已连接本地知识库「$displayName」，" +
            "当前已索引 ${docCount} 篇笔记、${chunkCount} 个知识分块。" +
            "你可以直接问具体主题，例如“知识库里关于 X 怎么说？”，我会在本机检索后回答。"
    }

    fun noReliableMatch(
        query: String,
        docCount: Int,
        threshold: Float
    ): String {
        val topic = query.trim().ifBlank { "这个问题" }
        return if (docCount <= 0) {
            "知识库尚未完成索引，现在无法查询「$topic」。请先在设置中同步笔记。"
        } else {
            val thresholdText = "%.2f".format(java.util.Locale.ROOT, threshold)
            "本地知识库已连接，但在当前最低相关度 $thresholdText 下，" +
                "没有找到与「$topic」可靠匹配的笔记。" +
                "请换用笔记中出现的关键词，或在设置中适当降低“最低相关度”后重试。"
        }
    }
}
