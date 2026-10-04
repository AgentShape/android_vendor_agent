package moe.uni.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 精简的 Markdown（ADR-0026 第 3 条）：标题、粗体、斜体、行内代码、代码块、有序和无序列表、引用；链接只显示文字。
 * 表格、图片、HTML 按原文显示。
 */
@Composable
fun Markdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(printable(text)) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val inline = InlineStyles(
        code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.88f, background = codeBackground),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        for (block in blocks) Block(block, style, inline)
    }
}

@Composable
private fun Block(block: MdBlock, style: TextStyle, inline: InlineStyles) {
    when (block) {
        is MdBlock.Paragraph -> Text(inline.render(block.text), style = style)
        is MdBlock.Heading -> Text(inline.render(block.text), style = headingStyle(block.level).copy(color = style.color))
        is MdBlock.Code -> CodeBlock(block.text)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(QUOTE_BAR).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall))
            Column(Modifier.padding(start = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                for (child in block.blocks) {
                    Block(child, style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), inline)
                }
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            block.items.forEachIndexed { i, item ->
                Row {
                    val marker = if (block.ordered) "${block.start + i}." else "•"
                    Text(marker, style = style, modifier = Modifier.widthIn(min = LIST_MARKER).padding(end = Spacing.s))
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        for (child in item) Block(child, style, inline)
                    }
                }
            }
        }
        MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 代码块：surfaceContainerHighest 底、small 圆角，等宽的 bodySmall，过长的行横向滚动。 */
@Composable
fun CodeBlock(text: String) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState()).padding(Spacing.m),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 标题用字体样式里的角色：一级 titleLarge，二级 titleMedium，其余 titleSmall。 */
@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

private val QUOTE_BAR = 4.dp
private val LIST_MARKER = 24.dp

sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data object Rule : MdBlock
}

/** 按行解析块级结构；列表项和引用里的内容递归解析。 */
object MarkdownParser {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(text: String): List<MdBlock> = parse(text.replace("\r\n", "\n").split('\n'))

    private fun parse(lines: List<String>): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++
                FENCE.containsMatchIn(line) -> {
                    val fence = FENCE.find(line)!!.groupValues[1]
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) body.add(lines[i++])
                    i++
                    blocks.add(MdBlock.Code(body.joinToString("\n")))
                }
                HEADING.matches(line) -> {
                    val m = HEADING.find(line)!!
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]))
                    i++
                }
                RULE.matches(line) -> {
                    blocks.add(MdBlock.Rule)
                    i++
                }
                QUOTE.matches(line) -> {
                    val body = ArrayList<String>()
                    while (i < lines.size && QUOTE.matches(lines[i])) body.add(QUOTE.find(lines[i++])!!.groupValues[1])
                    blocks.add(MdBlock.Quote(parse(body)))
                }
                BULLET.matches(line) || ORDERED.matches(line) -> i = list(lines, i, blocks)
                else -> {
                    val body = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) body.add(lines[i++].trim())
                    blocks.add(MdBlock.Paragraph(body.joinToString("\n")))
                }
            }
        }
        return blocks
    }

    /** 一个列表：同类标记、同样缩进的连续项；缩进更深的行和列表项内的续行属于当前项。 */
    private fun list(lines: List<String>, from: Int, out: MutableList<MdBlock>): Int {
        val first = BULLET.find(lines[from]) ?: ORDERED.find(lines[from])!!
        val ordered = ORDERED.matches(lines[from])
        val indent = first.groupValues[1].length
        val pattern = if (ordered) ORDERED else BULLET
        val items = ArrayList<List<String>>()
        var i = from
        while (i < lines.size) {
            val m = pattern.find(lines[i])
            if (m != null && m.groupValues[1].length == indent) {
                items.add(mutableListOf(m.groupValues[3]))
                i++
                continue
            }
            val line = lines[i]
            val nextIsItem = i + 1 < lines.size && pattern.find(lines[i + 1])?.groupValues?.get(1)?.length == indent
            when {
                line.isBlank() && nextIsItem -> i++
                line.isBlank() -> break
                leading(line) > indent -> { (items.last() as MutableList).add(line.drop(minOf(leading(line), indent + 4))); i++ }
                !startsBlock(line) -> { (items.last() as MutableList).add(line.trim()); i++ }
                else -> break
            }
        }
        out.add(MdBlock.ListBlock(ordered, if (ordered) first.groupValues[2].toInt() else 1, items.map { parse(it) }))
        return i
    }

    private fun leading(line: String) = line.length - line.trimStart().length

    private fun startsBlock(line: String) = FENCE.containsMatchIn(line) || HEADING.matches(line) || QUOTE.matches(line) ||
        BULLET.matches(line) || ORDERED.matches(line) || RULE.matches(line)
}

/** 行内语法：`代码`、**粗体**、__粗体__、*斜体*、_斜体_、~~删除线~~ 按原文、[文字](链接) 只显示文字。 */
class InlineStyles(private val code: SpanStyle) {
    fun render(text: String): AnnotatedString = buildAnnotatedString { append(this, text) }

    private fun append(out: AnnotatedString.Builder, text: String) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            out.append(plain.toString())
            plain.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    plain.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { plain.append(c); i++; continue }
                    flush()
                    out.withStyle(code) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                (c == '*' || c == '_') && text.startsWith("$c$c", i) -> {
                    val end = text.indexOf("$c$c", i + 2)
                    if (end <= i + 2) { plain.append(c); i++; continue }
                    flush()
                    out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(this, text.substring(i + 2, end)) }
                    i = end + 2
                }
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() && opens(text, i) -> {
                    val end = closing(text, i + 1, c)
                    if (end < 0) { plain.append(c); i++; continue }
                    flush()
                    out.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(this, text.substring(i + 1, end)) }
                    i = end + 1
                }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > 0) text.indexOf(')', close + 2) else -1
                    if (close < 0 || end < 0 || text.substring(i + 1, close).contains('\n')) { plain.append(c); i++; continue }
                    flush()
                    append(out, text.substring(i + 1, close))
                    i = end + 1
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** snake_case 这样词中间的下划线不算强调。 */
    private fun opens(text: String, i: Int) = text[i] == '*' || i == 0 || !text[i - 1].isLetterOrDigit()

    private fun closing(text: String, from: Int, c: Char): Int {
        var j = text.indexOf(c, from)
        while (j > 0) {
            val next = text.getOrNull(j + 1)
            if (!text[j - 1].isWhitespace() && (c == '*' || next == null || !next.isLetterOrDigit())) return j
            j = text.indexOf(c, j + 1)
        }
        return -1
    }

    private companion object {
        const val ESCAPABLE = "\\`*_[]()#+-.!>~|"
    }
}
