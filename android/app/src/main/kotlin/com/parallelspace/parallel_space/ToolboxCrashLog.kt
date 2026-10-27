package com.parallelspace.parallel_space

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工具箱专用诊断日志。写到应用 filesDir/toolbox_crash.log，
 * 设置页可查看 / 复制 / 分享，方便 HyperOS 上无 logcat 时定位悬浮窗消失。
 *
 * 未捕获异常会先落盘，再尽量用 AlarmManager 约 1 秒后拉起 [ToolboxOverlayService]
 * （进程被系统杀掉时 START_STICKY 也会重启；闹钟是双保险）。
 */
object ToolboxCrashLog {
    private const val TAG = "ToolboxCrashLog"
    private const val FILE = "toolbox_crash.log"
    private const val MAX_BYTES = 256 * 1024
    private const val RESTART_REQ = 9911
    private val handlerInstalled = AtomicBoolean(false)
    private val lock = Any()

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE)

    fun installHandler(ctx: Context, tag: String) {
        if (!handlerInstalled.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                append(app, "FATAL[$tag/${t.name}]", e)
                scheduleOverlayRestart(app, reason = "uncaught:$tag/${t.name}")
            } catch (_: Throwable) {
            }
            // 交给系统默认处理（通常杀进程）；START_STICKY / 闹钟会把悬浮窗拉回来
            try {
                prev?.uncaughtException(t, e)
            } catch (_: Throwable) {
            }
        }
        append(app, "INFO", "handler installed ($tag)")
    }

    /** 崩溃或看门狗发现异常后，约 800ms 再启悬浮窗服务。 */
    fun scheduleOverlayRestart(ctx: Context, reason: String = "manual") {
        val app = ctx.applicationContext
        try {
            if (!ToolboxController.enabled(app)) {
                append(app, "INFO", "skip restart ($reason): toolbox off")
                return
            }
            if (!OemAdapter.canDrawOverlay(app)) {
                append(app, "WARN", "skip restart ($reason): no overlay permission")
                return
            }
            val pkg = ToolboxController.lastPackage(app)
            val intent = Intent(app, ToolboxOverlayService::class.java).apply {
                if (pkg.isNotBlank()) putExtra("pkg", pkg)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = if (Build.VERSION.SDK_INT >= 26) {
                PendingIntent.getForegroundService(app, RESTART_REQ, intent, flags)
            } else {
                PendingIntent.getService(app, RESTART_REQ, intent, flags)
            }
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val whenElapsed = SystemClock.elapsedRealtime() + 800L
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, pi)
                }
                append(app, "INFO", "scheduled overlay restart in 800ms ($reason) pkg=$pkg")
            } catch (t: Throwable) {
                append(app, "WARN", "alarm restart failed, try direct ($reason)", t)
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (t2: Throwable) {
                    append(app, "ERROR", "direct restart failed ($reason)", t2)
                }
            }
        } catch (t: Throwable) {
            append(app, "ERROR", "scheduleOverlayRestart ($reason)", t)
        }
    }

    fun append(ctx: Context, level: String, message: String, error: Throwable? = null) {
        val sw = StringWriter()
        if (error != null) {
            error.printStackTrace(PrintWriter(sw))
        }
        appendRaw(ctx, level, message, sw.toString())
    }

    fun append(ctx: Context, level: String, error: Throwable) {
        append(ctx, level, error.message ?: error.javaClass.simpleName, error)
    }

    private fun appendRaw(ctx: Context, level: String, message: String, stack: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
            val block = buildString {
                append(ts).append(' ').append(level).append(' ').append(message).append('\n')
                if (stack.isNotBlank()) append(stack).append('\n')
                append("---\n")
            }
            synchronized(lock) {
                val f = file(ctx)
                f.appendText(block)
                if (f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.length - MAX_BYTES / 2))
                }
            }
            if (level.startsWith("FATAL") || level == "ERROR") {
                Log.e(TAG, message)
            } else {
                Log.i(TAG, message)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "append failed", t)
        }
    }

    fun read(ctx: Context): String = try {
        val f = file(ctx)
        if (!f.exists()) "（还没有工具箱日志）" else f.readText().ifBlank { "（日志为空）" }
    } catch (t: Throwable) {
        "读取失败：${t.message}"
    }

    fun clear(ctx: Context): Boolean = try {
        val f = file(ctx)
        if (f.exists()) f.writeText("")
        append(ctx, "INFO", "log cleared")
        true
    } catch (_: Throwable) {
        false
    }
}
