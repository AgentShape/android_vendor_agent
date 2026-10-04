package moe.uni.agent.backend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import moe.uni.agent.AgentFailure
import moe.uni.agent.framework.IAgentScreen

/**
 * AgentFramework 的一个 bound service（ADR-0022 第 2 条、ADR-0029 第 6 条）：BIND_AUTO_CREATE，AgentFramework 重启后系统会重新连接。
 * 绑定是异步的，取用时最多等 BIND_TIMEOUT_MS。
 */
class FrameworkBinding<T : IInterface>(private val context: Context, serviceClass: String, private val asInterface: (IBinder) -> T) {
    private val component = ComponentName(IAgentScreen.PACKAGE_NAME, serviceClass)
    private val lock = Object()
    private var service: T? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(lock) {
            service = asInterface(binder)
            lock.notifyAll()
        }

        override fun onServiceDisconnected(name: ComponentName) = synchronized(lock) { service = null }
    }

    fun bind() {
        context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() = context.unbindService(connection)

    fun await(): T = synchronized(lock) {
        val deadline = SystemClock.uptimeMillis() + BIND_TIMEOUT_MS
        var current = service
        while (current == null || !current.asBinder().isBinderAlive) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) throw AgentFailure(AgentFailure.Reason.AGENT_FRAMEWORK_UNAVAILABLE)
            lock.wait(remaining)
            current = service
        }
        current
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
    }
}
