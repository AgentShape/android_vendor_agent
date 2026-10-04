package moe.uni.agent.conversation.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import moe.uni.agent.AgentFailure

/** 会话表和记录表的读写。界面用 Flow 订阅，loop 在自己的线程上同步读写。 */
@Dao
abstract class ConversationDao {
    @Query("SELECT * FROM conversation ORDER BY updated DESC")
    abstract fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    abstract fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM item WHERE conversation_id = :id ORDER BY seq")
    abstract fun observeItems(id: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    abstract fun get(id: String): ConversationEntity?

    @Query("SELECT * FROM item WHERE conversation_id = :id ORDER BY seq")
    abstract fun items(id: String): List<ItemEntity>

    @Insert
    abstract fun insert(conversation: ConversationEntity)

    @Insert
    protected abstract fun insertItems(items: List<ItemEntity>): List<Long>

    @Query("UPDATE conversation SET updated = :time WHERE id = :id")
    protected abstract fun touch(id: String, time: Long)

    /** 追加记录并更新会话时间，返回各条的 seq。 */
    @Transaction
    open fun append(id: String, items: List<ItemEntity>, time: Long): List<Long> {
        val seqs = insertItems(items)
        touch(id, time)
        return seqs
    }

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    abstract fun rename(id: String, title: String)

    @Query("UPDATE conversation SET failure_reason = :reason, failure_detail = :detail WHERE id = :id")
    abstract fun setFailure(id: String, reason: AgentFailure.Reason?, detail: String?)

    @Query("UPDATE conversation SET context_tokens = :tokens WHERE id = :id")
    abstract fun setContextTokens(id: String, tokens: Long)

    @Query("UPDATE conversation SET summary = :summary, context_start = :start, context_tokens = 0 WHERE id = :id")
    abstract fun compact(id: String, summary: String, start: Long)

    /** 记录随外键级联删除。 */
    @Query("DELETE FROM conversation WHERE id = :id")
    abstract fun delete(id: String)
}
