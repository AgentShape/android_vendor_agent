package moe.uni.agent.framework.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** 设备信息（ADR-0029 第 6 条）：构建、ABI、电量、网络、存储、内存、语言和时区。屏幕信息见 screen_list。 */
internal class DeviceInfo(private val context: Context) {
    fun get(): JSONObject = Results.ok()
        .put(BUILD, JSONObject().put(MODEL, Build.MODEL).put(MANUFACTURER, Build.MANUFACTURER).put(BRAND, Build.BRAND)
            .put(DEVICE, Build.DEVICE).put(RELEASE, Build.VERSION.RELEASE).put(SDK, Build.VERSION.SDK_INT)
            .put(SECURITY_PATCH, Build.VERSION.SECURITY_PATCH).put(TYPE, Build.TYPE).put(FINGERPRINT, Build.FINGERPRINT)
            .put(ABIS, JSONArray(Build.SUPPORTED_ABIS.toList())))
        .put(BATTERY, battery())
        .put(NETWORK, network())
        .put(STORAGE, JSONObject().put(DATA, space(Environment.getDataDirectory().path))
            .put(USER_STORAGE, space(Environment.getExternalStorageDirectory().path)))
        .put(MEMORY, memory())
        .put(LOCALE, Locale.getDefault().toLanguageTag())
        .put(TIMEZONE, TimeZone.getDefault().id)
        .put(UPTIME_MS, SystemClock.elapsedRealtime())

    private fun battery(): JSONObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return JSONObject()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> STATUS_CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> STATUS_FULL
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> STATUS_DISCHARGING
            else -> STATUS_UNKNOWN
        }
        return JSONObject().put(PERCENT, if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put(STATUS, status).put(PLUGGED, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            .put(TEMPERATURE_C, intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
    }

    private fun network(): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return JSONObject().put(CONNECTED, false)
        val caps = manager.getNetworkCapabilities(network)
        val transports = JSONArray()
        for ((transport, name) in TRANSPORTS) if (caps?.hasTransport(transport) == true) transports.put(name)
        val addresses = manager.getLinkProperties(network)?.linkAddresses?.map { it.address.hostAddress }.orEmpty()
        return JSONObject().put(CONNECTED, true).put(TRANSPORTS_FIELD, transports)
            .put(VALIDATED, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put(ADDRESSES, JSONArray(addresses))
    }

    private fun space(path: String): JSONObject {
        val stat = StatFs(path)
        return JSONObject().put(TOTAL_BYTES, stat.totalBytes).put(FREE_BYTES, stat.availableBytes)
    }

    private fun memory(): JSONObject {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return JSONObject().put(TOTAL_BYTES, info.totalMem).put(FREE_BYTES, info.availMem).put(LOW, info.lowMemory)
    }

    private companion object {
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
        )

        const val STATUS_CHARGING = "charging"
        const val STATUS_FULL = "full"
        const val STATUS_DISCHARGING = "discharging"
        const val STATUS_UNKNOWN = "unknown"

        // 结果字段
        const val BUILD = "build"
        const val MODEL = "model"
        const val MANUFACTURER = "manufacturer"
        const val BRAND = "brand"
        const val DEVICE = "device"
        const val RELEASE = "release"
        const val SDK = "sdk"
        const val SECURITY_PATCH = "security_patch"
        const val TYPE = "type"
        const val FINGERPRINT = "fingerprint"
        const val ABIS = "abis"
        const val BATTERY = "battery"
        const val PERCENT = "percent"
        const val STATUS = "status"
        const val PLUGGED = "plugged"
        const val TEMPERATURE_C = "temperature_c"
        const val NETWORK = "network"
        const val CONNECTED = "connected"
        const val TRANSPORTS_FIELD = "transports"
        const val VALIDATED = "validated"
        const val ADDRESSES = "addresses"
        const val STORAGE = "storage"
        const val DATA = "data"
        const val USER_STORAGE = "user_storage"
        const val TOTAL_BYTES = "total_bytes"
        const val FREE_BYTES = "free_bytes"
        const val MEMORY = "memory"
        const val LOW = "low"
        const val LOCALE = "locale"
        const val TIMEZONE = "timezone"
        const val UPTIME_MS = "uptime_ms"
    }
}
