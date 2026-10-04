package moe.uni.agent.tools.shell

import java.io.File
import java.io.FileOutputStream

/**
 * exec 输出：完整内容写进文件，内存里只留尾部 50KiB（ADR-0011）。
 * 最后交给模型的是尾部 2000 行/50KiB；没有被截断时删除文件。
 */
class OutputCapture(val file: File) {
    private val output = FileOutputStream(file)
    private var tail = ByteArray(0)
    private var size = 0L

    fun append(bytes: ByteArray, n: Int) {
        output.write(bytes, 0, n)
        size += n
        val joined = tail + bytes.copyOf(n)
        tail = joined.copyOfRange(maxOf(0, joined.size - LIMIT), joined.size)
    }

    /** 返回交给模型的文本和是否被截断。 */
    fun finish(): Pair<String, Boolean> {
        output.close()
        var start = 0
        var lines = 0
        for (i in tail.lastIndex - 1 downTo 0) {
            if (tail[i] == '\n'.code.toByte() && ++lines == LINE_LIMIT) { start = i + 1; break }
        }
        // 从截断处开始的半个 UTF-8 字符跳过。
        while (start < tail.size && tail[start].toInt() and UTF8_CONTINUATION_MASK == UTF8_CONTINUATION) start++
        val truncated = start > 0 || size > tail.size
        if (!truncated) file.delete()
        return Pair(tail.copyOfRange(start, tail.size).toString(Charsets.UTF_8), truncated)
    }

    companion object {
        const val LIMIT = 50 * 1024
        private const val LINE_LIMIT = 2000
        private const val UTF8_CONTINUATION_MASK = 0xc0
        private const val UTF8_CONTINUATION = 0x80
    }
}
