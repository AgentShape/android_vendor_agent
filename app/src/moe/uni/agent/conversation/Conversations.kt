package moe.uni.agent.conversation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.room.Room
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import moe.uni.agent.AgentFailure
import moe.uni.agent.conversation.db.ConversationDao
import moe.uni.agent.conversation.db.ConversationDatabase
import moe.uni.agent.conversation.db.ConversationEntity

/**
 * 全部会话（ADR-0027）：界面订阅数据库的变化，agent loop 用 open 取得运行中的会话。
 * 数据库只在后台线程访问；界面发起的写入（重命名、删除）放到 io 线程上。
 */
object Conversations {
    class Summary(val id: String, val title: String, val updated: Long)

    /** 界面显示一个会话需要的内容。 */
    class View(val title: String, val items: List<ChatItem>, val failure: AgentFailure?) {
        companion object {
            /** 会话还不存在（新对话在第一次运行开始之前）。 */
            val EMPTY = View("", emptyList(), null)
        }
    }

    /** 旧的 JSON 会话目录，首次启动时删除，不迁移（ADR-0027 第 4 条）。 */
    private const val LEGACY_DIR = "conversations"
    private const val PREFS = "conversations"
    private const val KEY_CURRENT = "current"

    private lateinit var db: ConversationDatabase
    private lateinit var prefs: SharedPreferences
    private val io = Executors.newSingleThreadExecutor()
    private val dao: ConversationDao get() = db.dao()

    /** 正在查看的会话；新对话时为 null。 */
    val current = mutableStateOf<String?>(null)

    @Synchronized
    fun init(context: Context) {
        if (::db.isInitialized) return
        val app = context.applicationContext
        File(app.filesDir, LEGACY_DIR).deleteRecursively()
        db = Room.databaseBuilder(app, ConversationDatabase::class.java, ConversationDatabase.NAME)
            .fallbackToDestructiveMigration(true).build()
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current.value = prefs.getString(KEY_CURRENT, null)
    }

    /** 按更新时间倒序。 */
    fun list(): Flow<List<Summary>> = dao.observeAll().map { rows -> rows.map { Summary(it.id, it.title, it.updated) } }

    /** 只在这个会话变化时发出新值。 */
    fun observe(id: String): Flow<View> = combine(dao.observe(id), dao.observeItems(id)) { row, items ->
        row?.let { View(it.title, ChatItem.from(items), it.failureReason?.let { r -> AgentFailure(r, it.failureDetail.orEmpty()) }) }
            ?: View.EMPTY
    }.flowOn(Dispatchers.Default)

    fun select(id: String?) {
        current.value = id
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** agent loop 的线程上调用：读出会话的全部记录；还不存在时新建，标题取 prompt。 */
    fun open(id: String, prompt: String?): Conversation {
        val row = dao.get(id) ?: System.currentTimeMillis().let { now ->
            ConversationEntity(id, prompt?.let(Conversation::titleOf).orEmpty(), now, now).also(dao::insert)
        }
        return Conversation(dao, row, dao.items(id))
    }

    /** 会话标题，不存在时为 null。后台线程调用（观察窗的通知标题，ADR-0031 第 2 条）。 */
    fun title(id: String): String? = dao.get(id)?.title

    fun rename(id: String, title: String) = io.execute { dao.rename(id, title.trim()) }

    /** 调用方负责确认它没有在运行。记录随会话级联删除，内存里的截图一并释放。 */
    fun delete(id: String) {
        if (current.value == id) select(null)
        ScreenshotStore.forget(id)
        io.execute { dao.delete(id) }
    }
}
