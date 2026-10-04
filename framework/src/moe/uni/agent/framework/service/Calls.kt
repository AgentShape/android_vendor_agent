package moe.uni.agent.framework.service

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import android.util.Log
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentScreen

/**
 * 所有 binder 入口都经过这里：校验调用方 uid 属于 IAgentScreen.CALLER_PACKAGE（manifest 另有 signature 权限），
 * 以后的审批挂在这里（ADR-0001），并打 logcat 摘要（含耗时）。
 */
internal fun <T> Context.dispatch(name: String, block: () -> T): T {
    val uid = Binder.getCallingUid()
    if (uid != packageManager.getPackageUid(IAgentScreen.CALLER_PACKAGE, 0)) {
        throw SecurityException("调用方 uid $uid 不是 ${IAgentScreen.CALLER_PACKAGE}")
    }
    val start = SystemClock.uptimeMillis()
    try {
        return block()
    } finally {
        Log.i(FrameworkApp.TAG, "call $name ${SystemClock.uptimeMillis() - start}ms")
    }
}
