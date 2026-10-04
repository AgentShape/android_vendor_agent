package moe.uni.agent.conversation

import android.util.Base64

/**
 * 截图只在内存里（ADR-0022 第 12 条）：call_id 和序号 → data URL。只保留正在运行的那个会话的截图，
 * 换了会话或 app 重启后旧截图变为占位。编码好的字符串原样复用，已经发出的图片逐字节不变。
 */
object ScreenshotStore {
    private val images = HashMap<String, String>()
    private var owner: String? = null

    /** 只保留最近这么多张。 */
    private const val KEEP = 3

    /** 超出的数目向下取整到它的倍数后再替换，请求前缀大约每这么多步才变一次，其余请求都能命中 prompt cache。 */
    private const val DROP_BATCH = 10

    private const val DATA_URL_PREFIX = "data:image/png;base64,"
    private const val KEY_SEPARATOR = "#"

    fun key(callId: String, index: Int) = callId + KEY_SEPARATOR + index

    @Synchronized
    fun put(conversation: String, callId: String, pngs: List<ByteArray>) {
        own(conversation)
        pngs.forEachIndexed { i, png -> images[key(callId, i)] = DATA_URL_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP) }
    }

    /**
     * 按保留策略整理后返回还在内存里的截图。keys 是本次请求里所有截图按出现顺序的键：
     * 不在其中的（已被折叠掉的）一并释放；超出的按批次从最早的开始释放。
     */
    @Synchronized
    fun retain(conversation: String, keys: List<String>): Map<String, String> {
        own(conversation)
        images.keys.retainAll(keys.toSet())
        val present = keys.filter { it in images }
        val excess = present.size - KEEP
        if (excess > 0) present.take(excess - excess % DROP_BATCH).forEach { images.remove(it) }
        return HashMap(images)
    }

    /** 只读取，不改变保留状态（上下文压缩的摘要请求用，ADR-0028）。 */
    @Synchronized
    fun peek(conversation: String, keys: List<String>): Map<String, String> =
        if (owner == conversation) keys.mapNotNull { k -> images[k]?.let { k to it } }.toMap() else emptyMap()

    @Synchronized
    fun forget(conversation: String) {
        if (owner == conversation) images.clear()
    }

    private fun own(conversation: String) {
        if (owner != conversation) images.clear()
        owner = conversation
    }
}
