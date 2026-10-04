package moe.uni.agent.conversation.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import moe.uni.agent.AgentFailure

/** 一个会话（ADR-0027 第 2 条）。上下文相关的三列见 ADR-0028。 */
@Entity(tableName = "conversation", indices = [Index("updated")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    /** 上下文的起点（item.seq），之前的记录已压缩进 summary。 */
    @ColumnInfo(name = "context_start") val contextStart: Long = 0,
    val summary: String? = null,
    /** 上一次请求的 input_tokens 加 output_tokens。 */
    @ColumnInfo(name = "context_tokens") val contextTokens: Long = 0,
    @ColumnInfo(name = "failure_reason") val failureReason: AgentFailure.Reason? = null,
    @ColumnInfo(name = "failure_detail") val failureDetail: String? = null,
)
