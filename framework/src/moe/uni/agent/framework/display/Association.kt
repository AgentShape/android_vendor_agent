package moe.uni.agent.framework.display

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Process
import android.os.RemoteException
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.os.SystemClock
import android.util.Log
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentScreen

/**
 * 虚拟屏幕需要的 CDM 关联（ADR-0022 第 3 条、ADR-0029 第 7 条）。先找已有的 APP_STREAMING 关联；没有时请 agentd
 * 用写死的命令创建（那条命令要求 shell 或 root 身份），再等它生效（角色授予是异步的），不硬编码关联 id。
 */
internal object Association {
    fun ensure(context: Context): AssociationInfo {
        find(context)?.let { return it }
        val output = try {
            agentd().createScreenAssociation(Process.myUserHandle().identifier)
        } catch (e: RemoteException) {
            Log.w(FrameworkApp.TAG, "agentd createScreenAssociation 失败", e)
            throw noAssociation("agentd 创建关联失败")
        } catch (e: ServiceSpecificException) {
            Log.w(FrameworkApp.TAG, "agentd createScreenAssociation 失败", e)
            throw noAssociation("agentd 创建关联失败")
        }
        repeat(RETRIES) {
            SystemClock.sleep(RETRY_MS)
            find(context)?.let { return it }
        }
        Log.w(FrameworkApp.TAG, "创建关联后仍找不到，命令输出：${output.take(OUTPUT_MAX)}")
        throw noAssociation("创建关联后仍找不到关联")
    }

    private fun find(context: Context): AssociationInfo? =
        context.getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull {
            it.deviceProfile == AssociationRequest.DEVICE_PROFILE_APP_STREAMING && it.isSelfManaged
        }

    /** 开机时 agentd 可能还没注册，等它出现。 */
    private fun agentd(): IAgentd = IAgentd.Stub.asInterface(ServiceManager.waitForService(IAgentd.SERVICE_NAME))

    private fun noAssociation(message: String) = ServiceSpecificException(IAgentScreen.ERROR_NO_ASSOCIATION, message)

    private const val RETRIES = 10
    private const val RETRY_MS = 500L
    private const val OUTPUT_MAX = 1000
}
