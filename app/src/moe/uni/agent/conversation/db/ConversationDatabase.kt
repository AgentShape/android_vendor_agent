package moe.uni.agent.conversation.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 会话数据库（ADR-0027）。表结构变化时不写迁移，直接重建；也不导出 schema，表结构以本包的实体类为准（第 5 条）。
 */
@Database(entities = [ConversationEntity::class, ItemEntity::class], version = 1, exportSchema = false)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun dao(): ConversationDao

    companion object {
        const val NAME = "conversations.db"
    }
}
