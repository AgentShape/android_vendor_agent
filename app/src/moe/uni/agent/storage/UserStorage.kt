package moe.uni.agent.storage

import android.os.Environment
import java.io.File
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.framework.ToolError
import moe.uni.agent.tools.ToolFailure

/**
 * 用户存储（ADR-0029 第 2、5 条）：agent 的数据归用户。HOME 是 /sdcard/Agent，文件工具的范围是整个用户存储。
 * 访问 /sdcard 顶层需要"所有文件访问"（MANAGE_EXTERNAL_STORAGE）。本 app 是平台签名的，安装时就被授予这个权限；
 * appop 保持 default 时 Environment.isExternalStorageManager() 以权限为准，不需要另外设置 appop（2026-09-30 实测）。
 */
object UserStorage {
    /** 用户存储的真实路径（/sdcard 是指向它的链接）。 */
    val root: File get() = Environment.getExternalStorageDirectory().canonicalFile

    /** agent 的目录，与 Download 等平级；路径与 root shell 的 HOME 相同。 */
    val home: File get() = File(IAgentd.HOME_DIR)

    /** 启动时调用：创建 HOME。 */
    fun ensure() {
        home.mkdirs()
    }

    /** 规范化后必须落在用户存储内；其他路径只能在用户开启 root shell 后经 exec 访问。 */
    fun resolve(path: String): File {
        if (!path.startsWith(File.separator)) throw ToolFailure(ToolError.INVALID_ARGUMENT, "不是绝对路径：$path")
        val file = File(path).canonicalFile
        val base = root
        if (file != base && !file.path.startsWith(base.path + File.separator)) {
            throw ToolFailure(ToolError.PERMISSION_DENIED, "不在用户存储（/sdcard）内：$path")
        }
        return file
    }
}
