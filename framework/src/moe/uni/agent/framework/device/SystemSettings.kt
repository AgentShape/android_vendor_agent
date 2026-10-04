package moe.uni.agent.framework.device

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.ToolError
import org.json.JSONArray
import org.json.JSONObject

/** 系统设置的读写（ADR-0029 第 6 条）。system uid 可以写 secure、global。 */
internal class SystemSettings(private val context: Context) {
    fun get(namespace: String, key: String): JSONObject {
        if (key.isEmpty()) throw Results.invalid("key 为空")
        return Results.ok().put(VALUE, read(namespace, key) ?: JSONObject.NULL)
    }

    fun list(namespace: String, filter: String): JSONObject {
        check(namespace)
        val items = JSONArray()
        context.contentResolver.query(Uri.parse("content://settings/$namespace"), arrayOf(COLUMN_NAME, COLUMN_VALUE), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (filter.isNotEmpty() && !name.contains(filter, true)) continue
                    items.put(JSONObject().put(KEY, name).put(VALUE, cursor.getString(1) ?: JSONObject.NULL))
                }
            }
        return Results.ok().put(SETTINGS, items).put(COUNT, items.length())
    }

    fun put(namespace: String, key: String, value: String): JSONObject {
        if (key.isEmpty()) throw Results.invalid("key 为空")
        val resolver = context.contentResolver
        val written = try {
            when (namespace) {
                SYSTEM -> Settings.System.putString(resolver, key, value)
                SECURE -> Settings.Secure.putString(resolver, key, value)
                GLOBAL -> Settings.Global.putString(resolver, key, value)
                else -> throw invalidNamespace(namespace)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(FrameworkApp.TAG, "写入 $namespace/$key 被拒绝", e)
            return Results.failure(ToolError.PERMISSION_DENIED, "不能写入 $namespace/$key")
        } catch (e: SecurityException) {
            Log.w(FrameworkApp.TAG, "写入 $namespace/$key 被拒绝", e)
            return Results.failure(ToolError.PERMISSION_DENIED, "不能写入 $namespace/$key")
        }
        return if (written) Results.ok().put(VALUE, read(namespace, key) ?: JSONObject.NULL)
            else Results.failure(ToolError.FAILED, "写入 $namespace/$key 失败")
    }

    private fun read(namespace: String, key: String): String? {
        val resolver = context.contentResolver
        return when (namespace) {
            SYSTEM -> Settings.System.getString(resolver, key)
            SECURE -> Settings.Secure.getString(resolver, key)
            GLOBAL -> Settings.Global.getString(resolver, key)
            else -> throw invalidNamespace(namespace)
        }
    }

    private fun check(namespace: String) {
        if (namespace !in NAMESPACES) throw invalidNamespace(namespace)
    }

    private fun invalidNamespace(namespace: String) = Results.invalid("namespace 只能是 ${NAMESPACES.joinToString("、")}：$namespace")

    private companion object {
        const val SYSTEM = "system"
        const val SECURE = "secure"
        const val GLOBAL = "global"
        val NAMESPACES = listOf(SYSTEM, SECURE, GLOBAL)

        const val COLUMN_NAME = "name"
        const val COLUMN_VALUE = "value"

        // 结果字段
        const val KEY = "key"
        const val VALUE = "value"
        const val SETTINGS = "settings"
        const val COUNT = "count"
    }
}
