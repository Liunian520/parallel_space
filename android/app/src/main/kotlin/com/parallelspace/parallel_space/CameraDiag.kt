package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 相机诊断（1.3.29）。logcat 标签 ACam；同时写宿主 files/camera_diag.log，分身进程也写同一文件。 */
object CameraDiag {
    const val TAG = "ACam"
    private const val MAX = 256 * 1024

    private fun file(): File? = try {
        val ctx = try { top.niunaijun.blackbox.BlackBoxCore.getContext() } catch (_: Throwable) { null }
        val dir = ctx?.filesDir ?: return null
        File(dir, "camera_diag.log")
    } catch (_: Throwable) { null }

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = file() ?: return
            if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val who = try { top.niunaijun.blackbox.BlackBoxCore.getAppPackageName() } catch (_: Throwable) { null } ?: "host"
            f.appendText("$ts [${Process.myPid()} $who] $msg\n")
        } catch (_: Throwable) {}
    }

    fun read(ctx: Context): String = try { File(ctx.filesDir, "camera_diag.log").takeIf { it.exists() }?.readText()?.takeLast(60_000) ?: "" } catch (t: Throwable) { "读取失败: $t" }
    fun clear(ctx: Context) { try { File(ctx.filesDir, "camera_diag.log").delete() } catch (_: Throwable) {} }

    fun summarize(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Int, is Long, is Boolean -> v.toString()
        else -> {
            if (v.javaClass.name == "android.content.AttributionSourceState") {
                val c = v.javaClass
                fun g(n: String) = try { c.getField(n).get(v) } catch (_: Throwable) { "?" }
                val next = (g("next") as? Array<*>)?.joinToString(prefix = "[", postfix = "]") { summarize(it) } ?: "[]"
                "ASS(pkg=${g("packageName")},tag=${g("attributionTag")},uid=${g("uid")},pid=${g("pid")},dev=${g("deviceId")}," +
                    "token=${if (g("token") == null) "null" else "set"},next=$next)"
            } else v.javaClass.simpleName
        }
    }

    /** 宿主进程里用 Camera2 打开后置相机、等第一帧，验证本机相机和 Alter Ego 自身权限是否正常。 */
    @SuppressLint("MissingPermission")
    fun selfTest(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("机型: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        val granted = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        sb.append("Alter Ego 相机权限: ${if (granted) "已允许" else "未允许"}\n")
        if (!granted) return sb.append("请先允许 Alter Ego 使用相机后再自检。\n").toString()
        val ht = HandlerThread("cam-selftest").apply { start() }
        val h = Handler(ht.looper)
        var dev: CameraDevice? = null
        var reader: ImageReader? = null
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val ids = cm.cameraIdList
            sb.append("相机列表: ${ids.joinToString()}\n")
            val id = ids.firstOrNull() ?: return sb.append("没有可用相机\n").toString()
            val opened = CountDownLatch(1)
            var err: String? = null
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { dev = c; opened.countDown() }
                override fun onDisconnected(c: CameraDevice) { err = "disconnected"; c.close(); opened.countDown() }
                override fun onError(c: CameraDevice, e: Int) { err = "error=$e"; c.close(); opened.countDown() }
            }, h)
            if (!opened.await(4, TimeUnit.SECONDS)) return sb.append("打开相机超时\n").toString()
            val d = dev ?: return sb.append("打开相机失败: $err\n").toString()
            sb.append("打开相机: 成功\n")
            val r = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).also { reader = it }
            val frame = CountDownLatch(1)
            r.setOnImageAvailableListener({ ir -> ir.acquireLatestImage()?.close(); frame.countDown() }, h)
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                        s.setRepeatingRequest(req, null, h)
                    } catch (t: Throwable) { sb.append("预览请求失败: $t\n") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { sb.append("会话配置失败\n") }
            }, h)
            sb.append(if (frame.await(4, TimeUnit.SECONDS)) "预览帧: 收到（本机相机正常）\n" else "预览帧: 4 秒内没收到\n")
        } catch (t: Throwable) {
            sb.append("异常: ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            try { dev?.close() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            ht.quitSafely()
        }
        log("selfTest: " + sb.toString().replace("\n", " | "))
        return sb.toString()
    }
}
