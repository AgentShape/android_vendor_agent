package moe.uni.agent.conversation.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条 Responses 记录（ADR-0027 第 2 条），按追加顺序存放。body 是原样的 item JSON（不含本地字段），
 * 其余列供界面和上下文组装过滤、配对，不必解析 body。
 */
@Entity(
    tableName = "item",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversation_id", "seq"), Index("conversation_id", "call_id")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val type: ItemType,
    @ColumnInfo(name = "call_id") val callId: String? = null,
    val body: String,
    /** 工具结果的成败，其余为空。 */
    val ok: Boolean? = null,
    /** 这次工具结果带过几张截图；截图本身只在内存里（ADR-0022 第 12 条）。 */
    val screenshots: Int = 0,
    /** 一条用户消息或一次模型回复的第一项：上下文压缩只能切在这里（ADR-0028 第 2 条）。 */
    @ColumnInfo(name = "step_start") val stepStart: Boolean = false,
)

enum class ItemType { USER, ASSISTANT, FUNCTION_CALL, FUNCTION_OUTPUT, REASONING, OTHER }
