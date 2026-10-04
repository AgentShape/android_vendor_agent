package moe.uni.agent.tools.js

import android.os.FileObserver
import android.os.SystemClock
import android.util.Log
import java.io.File
import moe.uni.agent.framework.IAgentEvents
import moe.uni.agent.framework.IAgentScreen
import org.json.JSONArray
import org.json.JSONObject

/**
 * exec_js 一次运行的事件队列（ADR-0034 第 4 条）：AgentFramework 推来的屏幕和通知事件、watch() 的文件事件，按到达顺序编号。
 * 第一次建立起点时向 AgentFramework 注册监听，运行结束时（close）注销并停止文件监听。
 * changed 只用来唤醒和计算是否稳定，不放进队列；其余事件放进队列，只保留最近 MAX_EVENTS 条。
 * 事件的字段见 AgentFramework 的 control/ResultFields.kt（WaitEventFields）。
 */
class ScriptEvents {
    private val lock = Object()
    private val queue = ArrayDeque<Pair<Long, JSONObject>>()

    /** 最后一个序号；changed 也占序号，用来唤醒。 */
    private var seq = 0L

    /** 主屏幕和虚拟屏幕的 changed 次数（节点树的版本）与最后一次的时间。 */
    private val versions = LongArray(2)
    private val changedAt = LongArray(2)

    private var screen: IAgentScreen? = null
    private val listener = object : IAgentEvents.Stub() {
        override fun onEvent(event: String) = add(JSONObject(event))
    }

    private val watchers = HashMap<Int, List<FileObserver>>()
    private var nextWatch = 0

    /** 确保已向 AgentFramework 注册监听，返回起点：当前序号和时间。 */
    fun mark(connect: () -> IAgentScreen): JSONObject {
        if (screen == null) {
            val s = connect()
            s.addEventListener(listener)
            screen = s
        }
        return point()
    }

    /**
     * 开始监听这些路径（不递归），返回起点和 id。files 是 (脚本给的路径, 规范化后的文件)：
     * 事件的 path 用脚本给的写法，脚本按它匹配。
     */
    fun watch(connect: () -> IAgentScreen, files: List<Pair<String, File>>): JSONObject {
        val id = ++nextWatch
        watchers[id] = files.map { (shown, file) -> observer(shown.trimEnd('/'), file).also { it.startWatching() } }
        return mark(connect).put(ID, id)
    }

    fun unwatch(id: Int) {
        watchers.remove(id)?.forEach { it.stopWatching() }
    }

    /**
     * 等到序号超过 since（有新事件或变化）或超时，返回 {seq, events, version, idle}：events 是 since 之后的事件（不含 changed），
     * version 是两块屏幕的 changed 次数，idle 是两块屏幕从 max(最后一次变化, from) 到现在的毫秒数（from 是起点的时间）。
     * stop 为 true 时提前返回（停止按钮、脚本截止时间）。
     */
    fun await(since: Long, from: Long, timeoutMs: Long, stop: () -> Boolean): JSONObject = synchronized(lock) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (seq <= since && !stop()) {
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) break
            lock.wait(minOf(left, SLICE_MS))
        }
        val now = SystemClock.uptimeMillis()
        JSONObject().put(SEQ, seq)
            .put(EVENTS, JSONArray(queue.filter { it.first > since }.map { it.second }))
            .put(VERSION, JSONObject().put(MAIN, versions[MAIN_INDEX]).put(VIRTUAL, versions[VIRTUAL_INDEX]))
            .put(IDLE, JSONObject().put(MAIN, now - maxOf(changedAt[MAIN_INDEX], from))
                .put(VIRTUAL, now - maxOf(changedAt[VIRTUAL_INDEX], from)))
    }

    fun close() {
        watchers.values.forEach { list -> list.forEach { it.stopWatching() } }
        watchers.clear()
        try {
            screen?.removeEventListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "removeEventListener failed", e)
        }
        screen = null
    }

    private fun point() = synchronized(lock) { JSONObject().put(SEQ, seq).put(T, SystemClock.uptimeMillis()) }

    private fun add(event: JSONObject) = synchronized(lock) {
        seq++
        if (event.optString(TYPE) == CHANGED) {
            val i = if (event.optBoolean(MAIN)) MAIN_INDEX else VIRTUAL_INDEX
            versions[i]++
            changedAt[i] = SystemClock.uptimeMillis()
        } else {
            queue.addLast(seq to event)
            if (queue.size > MAX_EVENTS) queue.removeFirst()
        }
        lock.notifyAll()
    }

    private fun observer(shown: String, file: File): FileObserver = object : FileObserver(file, MASK) {
        override fun onEvent(event: Int, path: String?) {
            val change = when (event and ALL_EVENTS) {
                CREATE -> CREATED
                CLOSE_WRITE -> MODIFIED
                MOVED_TO -> MOVED
                DELETE, MOVED_FROM -> DELETED
                else -> return
            }
            add(JSONObject().put(TYPE, FILE).put(CHANGE, change).put(PATH, if (path == null) shown else "$shown/$path"))
        }
    }

    private companion object {
        const val TAG = "ScriptEvents"
        const val MAX_EVENTS = 2000
        const val SLICE_MS = 50L
        const val MASK = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.DELETE
        const val MAIN_INDEX = 0
        const val VIRTUAL_INDEX = 1

        // 返回给 prelude 的字段（exec_prelude.js 按这些名字读取）
        const val SEQ = "seq"
        const val T = "t"
        const val ID = "id"
        const val EVENTS = "events"
        const val VERSION = "version"
        const val IDLE = "idle"
        const val VIRTUAL = "virtual"

        // 事件字段：AgentFramework 的 WaitEventFields 与文件事件
        const val TYPE = "type"
        const val MAIN = "main"
        const val CHANGED = "changed"
        const val CHANGE = "change"
        const val FILE = "file"
        const val PATH = "path"
        const val CREATED = "created"
        const val MODIFIED = "modified"
        const val MOVED = "moved"
        const val DELETED = "deleted"
    }
}
