package cn.xxstudy.assistant.rag.db

/** Explicit additive migration; never drop the user's indexed source text. */
object KnowledgeSchemaMigration {
    const val VERSION = 3

    fun statements(oldVersion: Int, newVersion: Int): List<String> {
        require(oldVersion in 1 until newVersion && newVersion <= VERSION) { "Unsupported knowledge schema migration" }
        return buildList {
            if (oldVersion < 2) {
                add("ALTER TABLE documents ADD COLUMN size_bytes INTEGER NOT NULL DEFAULT 0")
                // v1 used incompatible pooling. Preserve raw text for keyword retrieval,
                // invalidate only dense vectors and force a rebuild on explicit sync.
                add("UPDATE chunks SET embedding = X''")
            }
        }
    }
}
