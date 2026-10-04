package moe.uni.agent.framework.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.ToolError
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用的列出、安装和卸载（ADR-0029 第 6 条）。安装和卸载经 PackageInstaller 完成，同步等待结果；
 * AgentFramework 是 system uid，不需要用户确认。安装包由调用方打开后以 fd 交过来，不需要中转目录。
 */
internal class Apps(private val context: Context) {
    fun list(filter: String, includeSystem: Boolean): JSONObject {
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (system && !includeSystem) return@mapNotNull null
            val label = app.loadLabel(pm).toString()
            if (filter.isNotEmpty() && !info.packageName.contains(filter, true) && !label.contains(filter, true)) return@mapNotNull null
            JSONObject().put(PACKAGE, info.packageName).put(LABEL, label).put(VERSION, info.versionName.orEmpty())
                .put(VERSION_CODE, info.longVersionCode).put(SYSTEM, system).put(ENABLED, app.enabled)
                .put(LAUNCHABLE, pm.getLaunchIntentForPackage(info.packageName) != null)
        }.sortedBy { it.getString(PACKAGE) }
        return Results.ok().put(APPS, JSONArray(apps)).put(COUNT, apps.size)
    }

    fun install(apk: ParcelFileDescriptor, size: Long): JSONObject {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (size > 0) params.setSize(size)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                ParcelFileDescriptor.AutoCloseInputStream(apk).use { input ->
                    session.openWrite(APK_NAME, 0, size).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            } catch (e: IOException) {
                Log.w(FrameworkApp.TAG, "写入安装包失败", e)
                session.abandon()
                return Results.failure(ToolError.FAILED, "写入安装包失败")
            }
            return await { session.commit(it) }
        }
    }

    fun uninstall(packageName: String): JSONObject {
        if (packageName.isEmpty()) throw Results.invalid("包名为空")
        return await { context.packageManager.packageInstaller.uninstall(packageName, it) }
    }

    /** 提交后同步等待 PackageInstaller 的结果广播。结果在主线程送达，调用方在 binder 线程上等。 */
    private fun await(submit: (IntentSender) -> Unit): JSONObject {
        val action = "$ACTION_RESULT.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        var result: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result = intent
                latch.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
            submit(pending.intentSender)
            if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) return Results.failure(ToolError.TIMEOUT, "${TIMEOUT_S} 秒内没有等到结果")
        } finally {
            context.unregisterReceiver(receiver)
        }
        val intent = result!!
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        return when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Results.ok().put(PACKAGE, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty())
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Results.failure(ToolError.PERMISSION_DENIED, "需要用户确认，没有执行")
            // PackageInstaller 给出的状态说明（如 INSTALL_FAILED_…）是系统的结果数据，原样保留。
            else -> Results.failure(code(status), message.ifEmpty { "PackageInstaller 状态 $status" })
        }
    }

    private fun code(status: Int) = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> ToolError.CANCELLED
        PackageInstaller.STATUS_FAILURE_BLOCKED -> ToolError.PERMISSION_DENIED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> ToolError.CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> ToolError.UNSUPPORTED
        PackageInstaller.STATUS_FAILURE_INVALID -> ToolError.INVALID_ARGUMENT
        else -> ToolError.FAILED
    }

    private companion object {
        const val APK_NAME = "base.apk"
        const val ACTION_RESULT = "moe.uni.agent.framework.action.INSTALL_RESULT"
        const val TIMEOUT_S = 120L

        // 结果字段
        const val APPS = "apps"
        const val COUNT = "count"
        const val PACKAGE = "package"
        const val LABEL = "label"
        const val VERSION = "version"
        const val VERSION_CODE = "version_code"
        const val SYSTEM = "system"
        const val ENABLED = "enabled"
        const val LAUNCHABLE = "launchable"
    }
}
