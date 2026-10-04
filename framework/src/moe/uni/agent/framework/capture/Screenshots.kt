package moe.uni.agent.framework.capture

import android.graphics.Bitmap
import android.os.OutcomeReceiver
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManagerGlobal
import android.window.ScreenCapture
import android.window.ScreenCaptureInternal
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import moe.uni.agent.framework.display.VirtualScreen

/**
 * 截图（ADR-0022 第 6、8 条）：虚拟屏幕从 ImageReader 取最近一帧；主屏幕用 ScreenCapture，
 * 安全内容按系统默认策略遮挡。原始分辨率，编码成 PNG 放进 memfd 交给 AgentApp（第 2 条）。
 * 主屏上有状态胶囊时（[exclude] 给出它的图层，ADR-0033 第 5 条）改用 WindowManager 的 captureDisplay 排除这一层。
 */
class Screenshots(private val virtual: VirtualScreen, private val exclude: (Int) -> SurfaceControl?) {
    /** 虚拟屏幕还没有画面时为 null。 */
    fun bitmap(display: Int): Bitmap? {
        if (display != Display.DEFAULT_DISPLAY) return virtual.screenshot()
        exclude(display)?.let { return captureExcluding(display, it) }
        val future = CompletableFuture<ScreenCapture.ScreenCaptureResult>()
        ScreenCapture.capture(ScreenCapture.ScreenCaptureParams.Builder(display).build(), Runnable::run,
            object : OutcomeReceiver<ScreenCapture.ScreenCaptureResult, Exception> {
                override fun onResult(result: ScreenCapture.ScreenCaptureResult) { future.complete(result) }
                override fun onError(error: Exception) { future.completeExceptionally(error) }
            })
        val capture = future.get(CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)
        val buffer = capture.hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, capture.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            buffer.close()
        }
    }

    /**
     * 排除一个图层截整块屏幕：WindowManager 按 display 的根图层截取并带上排除列表，需要 READ_FRAME_BUFFER。
     * 其余参数取默认值，与 ScreenCapture 的默认一致（安全内容遮挡）。
     */
    private fun captureExcluding(display: Int, layer: SurfaceControl): Bitmap? {
        // CaptureArgs.Builder 的类型参数是自引用的（Builder<T extends Builder<T>>），按星投影使用，返回值都是基类。
        val builder: ScreenCaptureInternal.CaptureArgs.Builder<*> = ScreenCaptureInternal.CaptureArgs.Builder<Nothing>()
        val args = builder.setExcludeLayers(arrayOf(layer)).build()
        val listener = ScreenCaptureInternal.createSyncCaptureListener()
        checkNotNull(WindowManagerGlobal.getWindowManagerService()).captureDisplay(display, args, listener)
        val shot = checkNotNull(listener.buffer) { "截图失败" }
        val buffer = shot.hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            buffer.close()
        }
    }

    /** PNG 放进 memfd，不放进 Parcel 正文。 */
    fun memfd(bitmap: Bitmap): ParcelFileDescriptor {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()
        val fd = Os.memfd_create(MEMFD_NAME, OsConstants.MFD_CLOEXEC)
        try {
            var written = 0
            while (written < png.size) written += Os.write(fd, png, written, png.size - written)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    companion object {
        private const val CAPTURE_TIMEOUT_S = 5L
        private const val PNG_QUALITY = 100
        private const val MEMFD_NAME = "screenshot"
    }
}
