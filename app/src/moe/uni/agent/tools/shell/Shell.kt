package moe.uni.agent.tools.shell

import java.io.File

/** exec 和 task 用的 shell（ADR-0011）：bash 可执行时用 bash，否则用 sh。 */
object Shell {
    private const val BASH = "/system_ext/bin/bash"
    private const val SH = "/system/bin/sh"
    private const val COMMAND_FLAG = "-c"

    val path: String get() = if (File(BASH).canExecute()) BASH else SH

    /** 给模型看的名字。 */
    val displayName: String get() = if (path == BASH) "bash" else "sh（mksh）"

    fun argv(command: String) = arrayOf(path, COMMAND_FLAG, command)
}
