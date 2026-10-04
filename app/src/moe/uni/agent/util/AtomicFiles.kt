package moe.uni.agent.util

import android.util.AtomicFile
import org.json.JSONObject

/** 整体替换写入：写临时文件后 rename，崩溃时不会留下半个文件。 */
fun AtomicFile.writeText(text: String) {
    val out = startWrite()
    try {
        out.write(text.toByteArray(Charsets.UTF_8))
        finishWrite(out)
    } catch (e: Exception) {
        failWrite(out)
        throw e
    }
}

/** 文件不存在时返回 null。 */
fun AtomicFile.readJson(): JSONObject? =
    if (baseFile.exists()) JSONObject(readFully().toString(Charsets.UTF_8)) else null
