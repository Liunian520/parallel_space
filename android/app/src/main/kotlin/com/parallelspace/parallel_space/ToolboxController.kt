package com.parallelspace.parallel_space

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** 以后加工具：实现这个接口并在 [ToolboxCatalog.register] 注册。悬浮窗按列表渲染。 */
interface ToolboxTool {
    val id: String
    val title: String
    val summary: String
}

object ToolboxCatalog {
    private val tools = mutableListOf<ToolboxTool>(PasswordVaultTool())

    fun register(tool: ToolboxTool) {
        if (tools.none { it.id == tool.id }) tools += tool
    }

    fun tools(): List<ToolboxTool> = tools.toList()
}

class PasswordVaultTool : ToolboxTool {
    override val id = "vault"
    override val title = "密码本"
    override val summary = "查看本分身账号，一键填入或复制；添加请到 App 密码本"
}

/**
 * 工具箱开关、剪贴板回退、无障碍被系统关掉时的提醒。
 * 悬浮窗本身在 [ToolboxOverlayService]。
 */
object ToolboxController {
    private const val PREFS = "toolbox"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_A11Y_WAS_ON = "a11y_was_on"
    private const val KEY_A11Y_WARNED = "a11y_warned"
    private const val KEY_LAST_PKG = "last_pkg"
    const val CHANNEL_PERM = "toolbox_perm"
    private const val NOTIFY_A11Y = 1002
    private const val NOTIFY_OVERLAY = 1003

    private val handler = Handler(Looper.getMainLooper())
    var onVaultAuthedListener: (() -> Unit)? = null

    fun install(app: Application) {
        try {
            val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.addAccessibilityStateChangeListener {
                noteAccessibility(app, OemAdapter.accessibilityOn(app))
            }
        } catch (_: Throwable) {
        }
    }

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /**
     * 开关写入用 [SharedPreferences.Editor.commit] 同步落盘，避免 tick 读到旧值。
     * 关闭时发 [ToolboxOverlayService.ACTION_USER_STOP]，不要只靠 tick 轮询。
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        val ok = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, on)
            .commit()
        ToolboxCrashLog.append(app, "INFO", "setEnabled on=$on commit=$ok")
        if (!on) {
            try {
                val stop = Intent(app, ToolboxOverlayService::class.java)
                    .setAction(ToolboxOverlayService.ACTION_USER_STOP)
                app.startService(stop)
            } catch (t: Throwable) {
                ToolboxCrashLog.append(app, "WARN", "ACTION_USER_STOP startService failed", t)
            }
            try {
                app.stopService(Intent(app, ToolboxOverlayService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    fun lastPackage(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_PKG, "").orEmpty()

    fun onCloneLaunched(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_PKG, pkg).apply()
        if (!enabled(app)) {
            ToolboxCrashLog.append(app, "INFO", "clone launched but toolbox off pkg=$pkg")
            return
        }
        if (!OemAdapter.canDrawOverlay(app)) {
            ToolboxCrashLog.append(app, "WARN", "clone launched without overlay permission pkg=$pkg")
            notifyOverlay(app)
            return
        }
        val i = Intent(app, ToolboxOverlayService::class.java).putExtra("pkg", pkg)
        try {
            ContextCompat.startForegroundService(app, i)
            ToolboxCrashLog.append(app, "INFO", "start overlay for pkg=$pkg")
        } catch (t: Throwable) {
            ToolboxCrashLog.append(app, "ERROR", "start overlay failed pkg=$pkg", t)
        }
    }

    fun onVaultAuthed() {
        handler.post { onVaultAuthedListener?.invoke() }
    }

    /** 用户从本应用打开过无障碍页，或服务曾经连上。之后若被系统关掉，提醒一次。 */
    fun armAccessibility(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_A11Y_WAS_ON, true).apply()
    }

    fun accessibilityLikelyKilled(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_A11Y_WAS_ON, false) && !OemAdapter.accessibilityOn(ctx)
    }

    fun noteAccessibility(ctx: Context, enabled: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (enabled) {
            p.edit().putBoolean(KEY_A11Y_WAS_ON, true).putBoolean(KEY_A11Y_WARNED, false).apply()
            return
        }
        if (!p.getBoolean(KEY_A11Y_WAS_ON, false) || p.getBoolean(KEY_A11Y_WARNED, false)) return
        p.edit().putBoolean(KEY_A11Y_WARNED, true).apply()
        notifyA11y(ctx)
    }

    /** 复制到剪贴板，不自动清除。 */
    fun copyText(ctx: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("平行空间", text))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 无障碍不可用时的退路：把密码（没有则用户名）放进剪贴板，不自动清除。
     */
    fun copySecret(ctx: Context, password: String, username: String) {
        copyText(ctx, password.ifEmpty { username })
    }

    /** "filled" | "copied" | "copied_no_field" | "copied_user" | "copied_pass" */
    fun fill(ctx: Context, username: String, password: String): String {
        val svc = ToolboxAccessibilityService.instance
        if (svc != null) {
            val ok = try {
                ToolboxAccessibilityService.fillOffMain(svc, username, password)
            } catch (t: Throwable) {
                ToolboxCrashLog.append(ctx, "ERROR", "fillOffMain", t)
                false
            }
            if (ok) return "filled"
            // 填入失败：优先复制密码，方便长按粘贴
            if (password.isNotEmpty() && copyText(ctx, password)) return "copied_no_field"
            if (username.isNotEmpty() && copyText(ctx, username)) return "copied_no_field"
            return "copied_no_field"
        }
        if (password.isNotEmpty() && copyText(ctx, password)) return "copied"
        if (username.isNotEmpty() && copyText(ctx, username)) return "copied"
        return "copied"
    }

    fun handle(activity: MainActivity, call: MethodCall, result: MethodChannel.Result): Boolean {
        when (call.method) {
            "setToolboxEnabled" -> {
                setEnabled(activity, call.argument<Boolean>("on") == true)
                result.success("ok")
            }
            "toolboxEnabled" -> result.success(enabled(activity))
            "overlayGranted" -> result.success(OemAdapter.canDrawOverlay(activity))
            "toolboxCurrentPackage" -> result.success(lastPackage(activity))
            "compatReport" -> result.success(OemAdapter.report(activity))
            "compatOpen" -> result.success(OemAdapter.openCheck(activity, call.argument<String>("id") ?: ""))
            "openUrl" -> result.success(openUrl(activity, call.argument<String>("url") ?: ""))
            "vaultUnlock" -> VaultAuth.prompt(activity) { result.success(it) }
            "vaultLock" -> {
                VaultStore.invalidateCache()
                result.success("ok")
            }
            "vaultList" -> result.success(listPayload(activity))
            "vaultUpsert" -> result.success(mutate(activity) { VaultStore.upsert(activity, call.arguments as? Map<*, *> ?: emptyMap<String, Any>()) })
            "vaultDelete" -> result.success(mutate(activity) { VaultStore.delete(activity, call.argument<String>("id") ?: "") })
            "vaultFill" -> result.success(fillEntry(activity, call.argument<String>("id") ?: ""))
            "vaultCopy" -> result.success(copyEntry(activity, call.argument<String>("id") ?: "", call.argument<String>("which") ?: "pass"))
            "compatChains" -> result.success(
                OemAdapter.allFamilies().associate { fam ->
                    fam.name to mapOf(
                        "overlay" to OemAdapter.overlayChainLabels(fam),
                        "accessibility" to OemAdapter.accessibilityChainLabels(fam),
                        "autostart" to OemAdapter.autoStartChainLabels(fam),
                        "shortcut" to OemAdapter.shortcutChainLabels(fam),
                        "notification" to OemAdapter.chainLabels("notification", fam),
                        "battery" to OemAdapter.chainLabels("battery", fam),
                    )
                }
            )
            "a11yGuideSeen" -> result.success(
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("a11y_guide_seen", false)
            )
            "a11yGuideMark" -> {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("a11y_guide_seen", true).apply()
                result.success("ok")
            }
            "toolboxCrashLog" -> result.success(ToolboxCrashLog.read(activity))
            "toolboxCrashLogClear" -> result.success(if (ToolboxCrashLog.clear(activity)) "ok" else "error")
            "toolboxCrashLogShare" -> {
                val body = ToolboxCrashLog.read(activity)
                result.success(
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "平行空间工具箱日志")
                            putExtra(Intent.EXTRA_TEXT, body)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(Intent.createChooser(send, "分享工具箱日志"))
                        "ok"
                    } catch (t: Throwable) {
                        ToolboxCrashLog.append(activity, "ERROR", "share log", t)
                        "error"
                    }
                )
            }
            else -> return false
        }
        return true
    }

    private fun listPayload(ctx: Context): Map<String, Any?> {
        return try {
            mapOf("locked" to false, "entries" to VaultStore.list(ctx).map { it.toMap() })
        } catch (e: Throwable) {
            mapOf("locked" to false, "error" to (e.message ?: "error"), "entries" to emptyList<Any>())
        }
    }

    private fun mutate(ctx: Context, block: () -> String): String {
        return try {
            block()
        } catch (e: Throwable) {
            "error"
        }
    }

    private fun fillEntry(ctx: Context, id: String): String {
        val entry = try {
            VaultStore.find(ctx, id)
        } catch (_: Throwable) {
            return "error"
        } ?: return "not_found"
        return fill(ctx, entry.username, entry.password)
    }

    private fun copyEntry(ctx: Context, id: String, which: String): String {
        val entry = try {
            VaultStore.find(ctx, id)
        } catch (_: Throwable) {
            return "error"
        } ?: return "not_found"
        val text = when (which) {
            "user", "username", "account" -> entry.username
            else -> entry.password
        }
        if (text.isEmpty()) return "empty"
        return if (copyText(ctx, text)) {
            if (which == "user" || which == "username" || which == "account") "copied_user" else "copied_pass"
        } else {
            "error"
        }
    }

    private fun openUrl(activity: Activity, url: String): String {
        if (!url.startsWith("https://")) return "error"
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toAndroidUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ok"
        } catch (_: Throwable) {
            "error"
        }
    }

    private fun String.toAndroidUri() = android.net.Uri.parse(this)

    private fun notifyOverlay(ctx: Context) {
        if (!canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx,
            21,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_compat", "overlay"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(ctx, NOTIFY_OVERLAY, "需要悬浮窗权限", "打开分身时才能显示工具箱。点此前往悬浮窗权限页。", open)
    }

    private fun notifyA11y(ctx: Context) {
        if (!canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx,
            22,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(ctx, NOTIFY_A11Y, "无障碍服务已关闭", "一键填入已改为复制到剪贴板。点此可重新开启。", open)
    }

    private fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun post(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_PERM, "权限提醒", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL_PERM)
                .setSmallIcon(R.drawable.ic_toolbox)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (_: Throwable) {
        }
    }
}
