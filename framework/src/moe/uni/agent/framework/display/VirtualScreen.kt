package moe.uni.agent.framework.display

import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.VirtualDeviceParams
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.hardware.input.IVirtualKeyboard
import android.hardware.input.IVirtualTouchscreen
import android.hardware.input.VirtualKeyEvent
import android.hardware.input.VirtualKeyboard
import android.hardware.input.VirtualKeyboardConfig
import android.hardware.input.VirtualTouchEvent
import android.hardware.input.VirtualTouchscreen
import android.hardware.input.VirtualTouchscreenConfig
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.ServiceSpecificException
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import java.time.Duration
import java.util.concurrent.Executor
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentScreen

/**
 * 常驻的 VirtualDevice 和虚拟屏幕（ADR-0022 第 3～7 条）。调用方持有 lock 串行化；
 * 系统停止虚拟屏幕时，释放也在同一把锁下进行。输出 surface 是自己持有的 ImageReader，截图直接取最近一帧。
 * 每次释放后调用 released（镜像随之失效，ADR-0031 第 3 条）。
 */
class VirtualScreen(
    private val context: Context,
    private val handler: Handler,
    private val lock: Any,
    private val listener: VirtualDeviceManager.ActivityListener,
    private val released: () -> Unit,
) {
    private val executor = Executor { handler.post(it) }
    private var device: VirtualDeviceManager.VirtualDevice? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var keyboard: IVirtualKeyboard? = null
    private var touchscreen: IVirtualTouchscreen? = null
    private var closeables = emptyList<AutoCloseable>()

    private val frameLock = Any()
    private var latest: Image? = null

    /** 最近一帧到达的 uptime，用来判断画面是否稳定。 */
    @Volatile var lastFrame = 0L
        private set

    /** 每一帧到达时以 display id 调用（等待用的变化事件，ADR-0034 第 4 条）。 */
    @Volatile var frameListener: ((Int) -> Unit)? = null

    var width = 0
        private set
    var height = 0
        private set

    val created: Boolean get() = display != null

    /** 虚拟屏幕的 display id；没有创建时报错。 */
    val id: Int get() = display?.display?.displayId
        ?: throw ServiceSpecificException(IAgentScreen.ERROR_UNAVAILABLE, "虚拟屏幕没有创建")

    val isOn: Boolean get() = display?.display?.state == Display.STATE_ON

    fun setup() {
        if (display != null) return
        val association = Association.ensure(context)

        val params = VirtualDeviceParams.Builder()
            .setName(NAME)
            .setLocalDeviceOnly(true)
            .setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
            // 音频、剪贴板、摄像头、传感器保持 DEFAULT（第 5 条，Q36）。
            .setDevicePolicy(VirtualDeviceParams.POLICY_TYPE_RECENTS, VirtualDeviceParams.DEVICE_POLICY_CUSTOM)
            // 未设置时是约 1000 年，显式固定（第 7 条，Q37）。
            .setScreenOffTimeout(SCREEN_OFF_TIMEOUT)
            .build()
        val vd = context.getSystemService(VirtualDeviceManager::class.java).createVirtualDevice(association.id, params)
        try {
            vd.addActivityListener(executor, listener)
            // 规格取主屏幕当前的有效配置（第 4 条）。
            val metrics = DisplayMetrics()
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
            reader = r
            r.setOnImageAvailableListener({ onFrame(it) }, handler)
            // VDM 默认的 flag 之上加 TRUSTED 和 STEAL_TOP_FOCUS_DISABLED（Q35），保持主屏幕的全局焦点。
            val config = VirtualDisplayConfig.Builder(NAME, width, height, metrics.densityDpi)
                .setSurface(r.surface)
                .setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED or DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
                .build()
            val vdisplay = vd.createVirtualDisplay(config, executor, object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.w(FrameworkApp.TAG, "虚拟屏幕已停止")
                    handler.post { synchronized(lock) { release() } }
                }
            }) ?: throw IllegalStateException("createVirtualDisplay 返回 null")
            display = vdisplay
            val id = vdisplay.display.displayId
            val kb = vd.createVirtualKeyboard(VirtualKeyboardConfig.Builder()
                .setAssociatedDisplayId(id).setInputDeviceName(KEYBOARD_NAME).setVendorId(VENDOR_ID).setProductId(KEYBOARD_PRODUCT_ID).build())
            val ts = vd.createVirtualTouchscreen(VirtualTouchscreenConfig.Builder(width, height)
                .setAssociatedDisplayId(id).setInputDeviceName(TOUCHSCREEN_NAME).setVendorId(VENDOR_ID).setProductId(TOUCHSCREEN_PRODUCT_ID).build())
            // 公开的 send 方法是 void，失败只打 warning；取内部接口拿到注入结果（第 10 条）。
            keyboard = field(VirtualKeyboard::class.java, "mVirtualKeyboard", kb)
            touchscreen = field(VirtualTouchscreen::class.java, "mVirtualTouchscreen", ts)
            closeables = listOf(kb, ts)
            device = vd
            Log.i(FrameworkApp.TAG, "虚拟屏幕已创建：device=${vd.deviceId} display=$id ${width}x$height ${metrics.densityDpi}dpi association=${association.id}")
        } catch (e: Exception) {
            device = vd
            release()
            throw e
        }
    }

    fun release() {
        closeables.forEach { runCatching { it.close() } }
        closeables = emptyList()
        keyboard = null
        touchscreen = null
        display?.let { runCatching { it.release() } }
        display = null
        device?.let { runCatching { it.close() } }
        device = null
        reader?.close()
        reader = null
        synchronized(frameLock) {
            latest?.close()
            latest = null
        }
        released()
    }

    /** 休眠时唤醒（第 7 条），返回是否确实唤醒过。 */
    fun wake(): Boolean {
        if (isOn) return false
        device?.wakeUp()
        val start = SystemClock.uptimeMillis()
        while (!isOn && SystemClock.uptimeMillis() - start < WAKE_TIMEOUT_MS) SystemClock.sleep(POLL_MS)
        return true
    }

    private fun onFrame(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        synchronized(frameLock) {
            latest?.close()
            latest = image
        }
        lastFrame = SystemClock.uptimeMillis()
        display?.display?.displayId?.let { frameListener?.invoke(it) }
    }

    /** 最近一帧；还没有画面时为 null。 */
    fun screenshot(): Bitmap? = synchronized(frameLock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer.rewind())
        val bitmap = if (full.width == image.width) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        bitmap.setHasAlpha(false)
        bitmap
    }

    /** action 使用 MotionEvent 的 DOWN/UP/MOVE，与 VirtualTouchEvent 的取值相同。 */
    fun touch(action: Int, x: Float, y: Float): Boolean = touchscreen?.sendTouchEvent(
        VirtualTouchEvent.Builder()
            .setPointerId(0)
            .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
            .setAction(action)
            .setX(x.coerceIn(0f, width - 1f))
            .setY(y.coerceIn(0f, height - 1f))
            .setPressure(if (action == VirtualTouchEvent.ACTION_UP) 0f else 1f)
            .build()
    ) ?: false

    fun key(code: Int, down: Boolean): Boolean = keyboard?.sendKeyEvent(
        VirtualKeyEvent.Builder().setKeyCode(code).setAction(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP).build()
    ) ?: false

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(cls: Class<*>, name: String, target: Any): T =
        cls.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    companion object {
        private const val NAME = "agent"
        private const val KEYBOARD_NAME = "agent-keyboard"
        private const val TOUCHSCREEN_NAME = "agent-touchscreen"
        private const val VENDOR_ID = 1
        private const val KEYBOARD_PRODUCT_ID = 1
        private const val TOUCHSCREEN_PRODUCT_ID = 2
        private const val MAX_IMAGES = 3
        private val SCREEN_OFF_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val WAKE_TIMEOUT_MS = 3000L
        private const val POLL_MS = 50L
    }
}
