package moe.uni.agent.tools.file

import android.os.SELinux
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import moe.uni.agent.framework.ToolError
import moe.uni.agent.storage.UserStorage
import moe.uni.agent.tools.ToolFailure
import moe.uni.agent.tools.ToolOutput
import org.json.JSONObject

/**
 * 文件操作（ADR-0029 第 5 条）：在 AgentApp 中以用户身份执行，范围是整个用户存储。
 * 参数、结果和语义沿用原来 agentd 的文件操作（ADR-0007）：覆盖写入先写同目录的临时文件再 rename，
 * 追加直接写到末尾；不自动创建父目录。exec_js 的 fs.* 直接复用这里。
 */
class FileOps {
    fun read(args: JSONObject): JSONObject = io(args) {
        val start = args.optInt(FileTools.START, 1)
        val count = args.optInt(FileTools.COUNT, FileTools.DEFAULT_LINES)
        if (start < 1 || count < 1) throw ToolFailure(ToolError.INVALID_ARGUMENT, "start 和 count 必须 >= 1")
        val lines = readLines(regular(args.getString(FileTools.PATH)), start, count)
        val text = lines.data.toString(Charsets.UTF_8).removeSuffix("\n")
        val numbered = if (text.isEmpty()) "" else text.split('\n').mapIndexed { i, line -> "${start + i}: $line" }.joinToString("\n")
        ToolOutput.ok().put(FileTools.OUTPUT, numbered).put(FileTools.TOTAL_LINES, lines.total)
            .put(FileTools.TRUNCATED, lines.truncated)
    }

    /** 读出全部文本，给 exec_js 的 fs.read() 用。 */
    fun readText(path: String): String = io(path) {
        val file = regular(path)
        if (file.length() > READ_TEXT_LIMIT) {
            throw ToolFailure(ToolError.UNSUPPORTED, "文件超过 ${READ_TEXT_LIMIT / 1024 / 1024}MiB，不能整个读取：$path")
        }
        file.readText()
    }

    fun readBytes(args: JSONObject): JSONObject = io(args) {
        val offset = args.optLong(FileTools.OFFSET)
        val length = args.optInt(FileTools.LENGTH, FileTools.DEFAULT_BYTES)
        if (offset < 0 || length !in 0..FileTools.MAX_CHUNK_BYTES) {
            throw ToolFailure(ToolError.INVALID_ARGUMENT, "offset 必须 >= 0，length 在 0～${FileTools.MAX_CHUNK_BYTES} 之间")
        }
        val data = RandomAccessFile(regular(args.getString(FileTools.PATH)), "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val n = input.read(buffer, total, length - total)
                if (n < 0) break
                total += n
            }
            buffer.copyOf(total)
        }
        ToolOutput.ok().put(FileTools.DATA_BASE64, Base64.encodeToString(data, Base64.NO_WRAP))
            .put(FileTools.NEXT_OFFSET, offset + data.size)
    }

    fun write(args: JSONObject): JSONObject = io(args) {
        val target = UserStorage.resolve(args.getString(FileTools.PATH))
        val data = content(args, FileTools.CONTENT)
        val start = if (args.optBoolean(FileTools.APPEND) && target.exists()) {
            // 追加到已有文件：写到末尾（非原子）；不存在时按新建处理。
            val start = countNewlines(regular(target.path)) + 1
            FileOutputStream(target, true).use {
                it.write(data)
                it.fd.sync()
            }
            start
        } else {
            publish(target) { it.write(data) }
            1
        }
        val stat = Os.stat(target.path)
        ToolOutput.ok().put(FileTools.START_LINE, start).put(FileTools.LINE_COUNT, countLines(data))
            .put(FileTools.SIZE, stat.st_size).put(FileTools.MTIME_MS, mtimeMs(stat))
    }

    fun edit(args: JSONObject): JSONObject = io(args) {
        val target = regular(args.getString(FileTools.PATH))
        val old = content(args, FileTools.OLD)
        val new = content(args, FileTools.NEW)
        val expected = args.getInt(FileTools.EXPECTED_COUNT)
        val expectedMtime = args.optLong(FileTools.EXPECTED_MTIME_MS)
        if (old.isEmpty() || expected < 0) throw ToolFailure(ToolError.INVALID_ARGUMENT, "old 不能为空，expected_count 必须 >= 0")
        if (expectedMtime != 0L && mtimeMs(Os.stat(target.path)) != expectedMtime) {
            throw ToolFailure(ToolError.CONFLICT, "文件的 mtime 与 expected_mtime_ms 不符，没有修改")
        }
        val (replaced, count) = replaceAll(target.readBytes(), old, new)
        if (count != expected) throw ToolFailure(ToolError.CONFLICT, "预期匹配 $expected 次，实际 $count 次，没有修改")
        if (count > 0) publish(target) { it.write(replaced) }
        val stat = Os.stat(target.path)
        ToolOutput.ok().put(FileTools.EDIT_COUNT, count).put(FileTools.SIZE, stat.st_size).put(FileTools.MTIME_MS, mtimeMs(stat))
    }

    /** 不跟随符号链接。 */
    fun stat(args: JSONObject): JSONObject = io(args) {
        val file = UserStorage.resolve(args.getString(FileTools.PATH))
        val s = Os.lstat(file.path)
        ToolOutput.ok().put(FileTools.MODE, Integer.toOctalString(s.st_mode)).put(FileTools.UID, s.st_uid).put(FileTools.GID, s.st_gid)
            .put(FileTools.SIZE, s.st_size).put(FileTools.MTIME_MS, mtimeMs(s))
            .put(FileTools.SE_CONTEXT, SELinux.getFileContext(file.path).orEmpty())
    }

    /** 按名字排序后取 [start, start + max)；一页超过 MAX_CHUNK_BYTES 时提前结束，调用方从下一项继续。 */
    fun list(args: JSONObject): JSONObject = io(args) {
        val dir = UserStorage.resolve(args.getString(FileTools.PATH))
        val start = args.optInt(FileTools.LIST_START, 0)
        val max = args.optInt(FileTools.MAX, FileTools.DEFAULT_ENTRIES)
        if (start < 0 || max < 1) throw ToolFailure(ToolError.INVALID_ARGUMENT, "start 必须 >= 0，max 必须 >= 1")
        if (!OsConstants.S_ISDIR(Os.stat(dir.path).st_mode)) throw ErrnoException("stat", OsConstants.ENOTDIR)
        val names = (dir.list() ?: throw ToolFailure(ToolError.FAILED, "无法列出目录：${dir.path}")).sorted()
        val lines = ArrayList<String>()
        var size = 0
        for (name in names.drop(start).take(max)) {
            val line = "${typeName(Os.lstat(File(dir, name).path).st_mode)} $name"
            if (size + line.length > FileTools.MAX_CHUNK_BYTES) break
            lines += line
            size += line.length + 1
        }
        ToolOutput.ok().put(FileTools.OUTPUT, lines.joinToString("\n")).put(FileTools.TOTAL, names.size)
            .put(FileTools.NEXT_START, start + lines.size)
    }

    private class Lines(val data: ByteArray, val total: Int, val truncated: Boolean)

    /** 逐行读到文件末尾，同时统计总行数；返回的字节不超过 MAX_CHUNK_BYTES。 */
    private fun readLines(file: File, first: Int, count: Int): Lines {
        val last = first.toLong() + count - 1
        val data = ByteArrayOutputStream()
        val current = ByteArrayOutputStream()
        var line = 1L
        var truncated = false
        fun endLine() {
            if (line in first..last) {
                if (data.size() + current.size() <= FileTools.MAX_CHUNK_BYTES) current.writeTo(data) else truncated = true
            }
            current.reset()
            line++
        }
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var from = 0
                for (i in 0 until n) {
                    if (buffer[i] == NEWLINE) {
                        current.write(buffer, from, i + 1 - from)
                        endLine()
                        from = i + 1
                    }
                }
                current.write(buffer, from, n - from)
            }
        }
        if (current.size() > 0) endLine()
        return Lines(data.toByteArray(), (line - 1).toInt(), truncated)
    }

    /** 覆盖写入：先写同目录的临时文件，同步后 rename 到目标。 */
    private fun publish(target: File, write: (FileOutputStream) -> Unit) {
        if (target.isDirectory) throw ErrnoException("open", OsConstants.EISDIR)
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileOutputStream(temporary).use {
                write(it)
                it.fd.sync()
            }
            Os.rename(temporary.path, target.path)
        } finally {
            temporary.delete()
        }
    }

    /** 必须落在用户存储内，且是普通文件。 */
    private fun regular(path: String): File {
        val file = UserStorage.resolve(path)
        if (!OsConstants.S_ISREG(Os.stat(file.path).st_mode)) throw ToolFailure(ToolError.INVALID_ARGUMENT, "不是普通文件：$path")
        return file
    }

    /** 模型常把文本和 Base64 两种参数都填上、其中一个为空，所以非空的 Base64 优先。 */
    private fun content(args: JSONObject, name: String): ByteArray {
        val base64 = args.optString(name + FileTools.BASE64_SUFFIX)
        return if (base64.isNotEmpty()) Base64.decode(base64, Base64.DEFAULT) else args.optString(name).toByteArray()
    }

    private fun countNewlines(file: File): Int {
        var count = 0
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return count
                for (i in 0 until n) if (buffer[i] == NEWLINE) count++
            }
        }
    }

    /** 行数：换行符个数，最后一段没有换行时再加一行。 */
    private fun countLines(data: ByteArray): Int = data.count { it == NEWLINE } + if (data.isNotEmpty() && data.last() != NEWLINE) 1 else 0

    /** 全部不重叠的匹配都替换，返回新内容和替换次数。 */
    private fun replaceAll(data: ByteArray, old: ByteArray, new: ByteArray): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream(data.size)
        var count = 0
        var from = 0
        var i = 0
        while (i <= data.size - old.size) {
            if (matches(data, i, old)) {
                out.write(data, from, i - from)
                out.write(new)
                count++
                i += old.size
                from = i
            } else {
                i++
            }
        }
        out.write(data, from, data.size - from)
        return out.toByteArray() to count
    }

    private fun matches(data: ByteArray, at: Int, pattern: ByteArray): Boolean {
        for (j in pattern.indices) if (data[at + j] != pattern[j]) return false
        return true
    }

    private fun typeName(mode: Int) = when {
        OsConstants.S_ISDIR(mode) -> TYPE_DIR
        OsConstants.S_ISLNK(mode) -> TYPE_LINK
        OsConstants.S_ISREG(mode) -> TYPE_FILE
        else -> TYPE_OTHER
    }

    private fun mtimeMs(s: StructStat) = s.st_mtim.tv_sec * 1000 + s.st_mtim.tv_nsec / 1_000_000

    private inline fun <T> io(args: JSONObject, block: () -> T): T = io(args.optString(FileTools.PATH), block)

    /** 系统调用错误用 errno 名描述，带上参数里的路径；其他 IO 错误记日志。 */
    private inline fun <T> io(path: String, block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw ToolOutput.errno(e.errno, path)
    } catch (e: IOException) {
        (e.cause as? ErrnoException)?.let { throw ToolOutput.errno(it.errno, path) }
        Log.w(TAG, "io failed", e)
        throw ToolFailure(ToolError.FAILED, "读写失败：$path")
    }

    private companion object {
        const val TAG = "FileOps"
        const val READ_TEXT_LIMIT = 16 * 1024 * 1024
        const val BUFFER_BYTES = 65536
        const val NEWLINE = '\n'.code.toByte()
        const val TEMP_SUFFIX = ".tmp"

        // list 输出里的类型名
        const val TYPE_DIR = "dir"
        const val TYPE_LINK = "link"
        const val TYPE_FILE = "file"
        const val TYPE_OTHER = "other"
    }
}
