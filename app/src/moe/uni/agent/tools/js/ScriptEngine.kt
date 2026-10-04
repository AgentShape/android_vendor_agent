package moe.uni.agent.tools.js

import android.content.Context
import moe.uni.agent.framework.ToolError
import org.json.JSONObject

/**
 * exec_js 的 QuickJS 运行时（ADR-0024），每个对话一个，globalThis 在同一对话的多次调用之间保留。
 * 只能在同一个线程上使用（agent loop 的执行线程）；interrupt 可以从任何线程调用。
 */
class ScriptEngine(context: Context) : AutoCloseable {
    /** 宿主函数的实现，由 JNI 按名字从脚本里同步调用：name 和 UTF-8 的 JSON 参数，返回 UTF-8 的 JSON。 */
    interface Host {
        fun call(name: String, args: ByteArray): ByteArray
    }

    /**
     * result 是 __run 的返回值：{"ok":true,"value":"<JSON>"} 或 {"ok":false,"error":{…},"stack":…}（格式见 JsHost）。
     * uncaught 不为 null 表示有 __run 捕获不到的异常：被停止、超时或内存耗尽。
     */
    class Outcome(val result: String, val uncaught: String?)

    private var ptr = nativeCreate(MEMORY_LIMIT, STACK_LIMIT)

    init {
        val prelude = context.assets.open(PRELUDE).use { it.readBytes() }
        val result = nativeEval(ptr, NO_HOST, prelude, PRELUDE, 0)
        check(result[0] == RESULT) { "prelude 执行失败：${String(result, 1, result.size - 1)}" }
    }

    /** shell 为 false 时脚本里没有 sh()：运行时跨调用保留，所以每次按这次运行的设置定义或删除它。 */
    fun run(host: Host, code: String, timeoutMs: Long, shell: Boolean): Outcome {
        // 由 prelude 的 __run 用间接 eval 执行：let/const 只在这次调用内有效，重复声明不会报错；要跨调用保留就挂到 globalThis 上。
        val wrapper = "${JsHost.RUN}(${JSONObject.quote(code)}, $shell)"
        val result = nativeEval(ptr, host, wrapper.toByteArray(), SCRIPT_NAME, timeoutMs)
        val text = String(result, 1, result.size - 1, Charsets.UTF_8)
        return if (result[0] == RESULT) Outcome(text, null) else Outcome("", text)
    }

    fun interrupt() = nativeInterrupt(ptr)

    override fun close() {
        if (ptr != 0L) nativeDestroy(ptr)
        ptr = 0
    }

    private object NO_HOST : Host {
        override fun call(name: String, args: ByteArray): ByteArray = JsHost.error(ToolError.FAILED, "prelude 中不能调用宿主函数")
    }

    companion object {
        private const val PRELUDE = "exec_prelude.js"
        private const val SCRIPT_NAME = "exec_js"
        private const val MEMORY_LIMIT = 64L * 1024 * 1024
        private const val STACK_LIMIT = 256L * 1024

        /** nativeEval 返回值的第一个字节：'R' 为结果，'E' 为未捕获的异常（libagentjs 的约定）。 */
        private const val RESULT = 'R'.code.toByte()

        init {
            System.loadLibrary("agentjs")
        }

        @JvmStatic private external fun nativeCreate(memoryLimit: Long, stackLimit: Long): Long
        @JvmStatic private external fun nativeDestroy(ptr: Long)
        @JvmStatic private external fun nativeInterrupt(ptr: Long)
        @JvmStatic private external fun nativeEval(ptr: Long, host: Host, code: ByteArray, name: String, timeoutMs: Long): ByteArray
    }
}
