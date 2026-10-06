package cn.xxstudy.assistant.rag

enum class RagKnowledgeStage(val label: String) {
    RETRIEVING("检索知识库…"),
    GENERATING("生成资料摘要 · 引用尚未核对…"),
    CHECKING("核对资料引用…")
}
