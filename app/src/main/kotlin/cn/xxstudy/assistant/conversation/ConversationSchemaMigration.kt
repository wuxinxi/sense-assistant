package cn.xxstudy.assistant.conversation

object ConversationSchemaMigration {
    const val VERSION = 3
    fun statements(oldVersion: Int, newVersion: Int): List<String> {
        require(oldVersion in 1 until newVersion && newVersion <= VERSION) { "Unsupported conversation schema migration" }
        return buildList {
            if (oldVersion < 2) {
                add("ALTER TABLE conversation_turns ADD COLUMN retrieval_topic TEXT NOT NULL DEFAULT ''")
                add("ALTER TABLE conversation_turns ADD COLUMN source_required INTEGER NOT NULL DEFAULT 0")
            }
            if (newVersion >= 3) add("ALTER TABLE conversation_turns ADD COLUMN display_snapshot BLOB")
        }
    }
}
