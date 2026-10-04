package moe.uni.agent.tools.file

import moe.uni.agent.tools.Schema
import moe.uni.agent.tools.Tool
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolResult
import org.json.JSONObject

/** 文件工具（ADR-0007、ADR-0013）：工具名、参数和结果字段，以及工具列表。执行在 FileOps。 */
object FileTools {
    // 工具名
    const val READ = "read"
    const val READ_BYTES = "read_bytes"
    const val WRITE = "write"
    const val EDIT = "edit"
    const val STAT = "stat"
    const val LIST = "list"

    // 参数（exec_js 的 fs.* 用同样的名字）
    const val PATH = "path"
    const val OFFSET = "offset"
    const val LENGTH = "length"
    const val CONTENT = "content"
    const val APPEND = "append"
    const val OLD = "old"
    const val NEW = "new"
    const val EXPECTED_COUNT = "expected_count"
    const val EXPECTED_MTIME_MS = "expected_mtime_ms"
    const val START = "start"
    const val COUNT = "count"
    const val LIST_START = "start"
    const val MAX = "max"
    const val BASE64_SUFFIX = "_base64"

    // 结果字段
    const val OUTPUT = "output"
    const val TOTAL_LINES = "total_lines"
    const val TRUNCATED = "truncated"
    const val DATA_BASE64 = "data_base64"
    const val NEXT_OFFSET = "next_offset"
    const val START_LINE = "start_line"
    const val LINE_COUNT = "line_count"
    const val SIZE = "size"
    const val MTIME_MS = "mtime_ms"
    const val EDIT_COUNT = "count"
    const val MODE = "mode"
    const val UID = "uid"
    const val GID = "gid"
    const val SE_CONTEXT = "se_context"
    const val TOTAL = "total"
    const val NEXT_START = "next_start"

    /** 一次读取或列出返回的数据上限。 */
    const val MAX_CHUNK_BYTES = 256 * 1024

    const val DEFAULT_LINES = 200
    const val DEFAULT_BYTES = 32768
    const val DEFAULT_ENTRIES = 200

    /** 文件工具的工具组说明。 */
    const val GROUP_DESCRIPTION = "文件工具：以用户身份执行，范围是整个用户存储 /sdcard，路径用绝对路径。"

    val ALL: List<Tool> = listOf(Read, ReadBytes, Write, Edit, Stat, ListDir)
}

/** 文件工具的公共部分：参数都以绝对路径开头，摘要显示路径。 */
private abstract class FileTool(override val name: String, override val description: String) : Tool {
    override val kind get() = Tool.Kind.FILE
    override val groupDescription get() = FileTools.GROUP_DESCRIPTION

    override fun summary(args: JSONObject): String = args.optString(FileTools.PATH)

    override fun parameters(schema: Schema) {
        schema.string(FileTools.PATH, "用户存储中的绝对路径（/sdcard/...）", required = true)
        more(schema)
    }

    /** path 之后的参数。 */
    protected open fun more(schema: Schema) {}
}

private object Read : FileTool(FileTools.READ, "按行读取文本文件，输出带行号。") {
    override fun more(schema: Schema) = with(schema) {
        integer(FileTools.START, "起始行，默认 1")
        integer(FileTools.COUNT, "行数，默认 ${FileTools.DEFAULT_LINES}")
    }

    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(context.files.read(args))
}

private object ReadBytes : FileTool(FileTools.READ_BYTES, "读取原始字节（二进制文件或超长行），返回 Base64。") {
    override fun more(schema: Schema) = with(schema) {
        integer(FileTools.OFFSET, "字节偏移，默认 0")
        integer(FileTools.LENGTH, "字节数，默认 ${FileTools.DEFAULT_BYTES}")
    }

    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(context.files.readBytes(args))
}

private object Write : FileTool(FileTools.WRITE, "写文件：默认原子覆盖，append=true 时追加到末尾。返回写入内容的起始行号和行数。") {
    override fun more(schema: Schema) = with(schema) {
        string(FileTools.CONTENT, "文本")
        string(FileTools.CONTENT + FileTools.BASE64_SUFFIX, "二进制内容的 Base64，与 ${FileTools.CONTENT} 二选一")
        boolean(FileTools.APPEND, "追加，默认 false")
    }

    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(context.files.write(args))
}

private object Edit : FileTool(
    FileTools.EDIT,
    "把文件中的 old 精确替换为 new；实际匹配次数必须等于 expected_count，否则不修改（conflict），这时先 ${FileTools.READ} 确认原文。",
) {
    override fun more(schema: Schema) = with(schema) {
        string(FileTools.OLD, "原文本")
        string(FileTools.NEW, "新文本")
        string(FileTools.OLD + FileTools.BASE64_SUFFIX, "原字节的 Base64，与 ${FileTools.OLD} 二选一")
        string(FileTools.NEW + FileTools.BASE64_SUFFIX, "新字节的 Base64，与 ${FileTools.NEW} 二选一")
        integer(FileTools.EXPECTED_COUNT, "预期匹配次数", required = true)
        integer(FileTools.EXPECTED_MTIME_MS, "文件应有的 mtime（来自 stat 或上次写入）；不符时拒绝修改")
    }

    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(context.files.edit(args))
}

private object Stat : FileTool(FileTools.STAT, "文件元数据，不跟随符号链接。") {
    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(context.files.stat(args))
}

private object ListDir : FileTool(FileTools.LIST, "列出目录，按名字排序分页。") {
    override fun more(schema: Schema) = with(schema) {
        integer(FileTools.LIST_START, "起始序号，默认 0")
        integer(FileTools.MAX, "条数，默认 ${FileTools.DEFAULT_ENTRIES}")
    }

    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(context.files.list(args))
}
