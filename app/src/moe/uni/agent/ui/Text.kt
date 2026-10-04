package moe.uni.agent.ui

/** 文本里不能有 NUL 等控制字符（uiautomator 会崩溃）。 */
fun printable(text: String): String =
    text.map { if (it.isISOControl() && it != '\n' && it != '\t') '�' else it }.joinToString("")
