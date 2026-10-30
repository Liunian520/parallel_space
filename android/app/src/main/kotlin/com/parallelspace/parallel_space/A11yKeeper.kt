package com.parallelspace.parallel_space

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 1.3.27 无障碍常驻：拿到 WRITE_SECURE_SETTINGS 后，被系统关掉也能自动重新打开。
 * 授权方式优先级：Shizuku（免电脑）> Root > 电脑 adb。
 */
object A11yKeeper {
    fun flat(ctx: Context) = ComponentName(ctx, ToolboxAccessibilityService::class.java).flattenToString()

    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun adbCommand(ctx: Context) = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun enabledInSettings(ctx: Context): Boolean = try {
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        raw.split(':').any { it.equals(flat(ctx), true) } &&
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    } catch (_: Throwable) { false }

    /** 若有权限且当前未开，写回 Settings.Secure。返回是否做了恢复。 */
    fun ensure(ctx: Context, reason: String): Boolean {
        return try {
            if (!canWriteSecure(ctx) || enabledInSettings(ctx)) return false
            val cr = ctx.contentResolver
            val me = flat(ctx)
            val raw = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = raw.split(':').filter { it.isNotBlank() && !it.equals(me, true) } + me
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            ToolboxCrashLog.append(ctx, "INFO", "a11y auto-restored ($reason)")
            true
        } catch (t: Throwable) {
            ToolboxCrashLog.append(ctx, "WARN", "a11y restore failed ($reason)", t)
            false
        }
    }

    // ---------- Shizuku ----------
    fun shizukuInstalled(ctx: Context) = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (_: Throwable) { false }

    fun shizukuRunning() = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun shizukuGranted() = try {
        shizukuRunning() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private const val REQ = 4127

    /** 返回 not_installed / not_running / requested / ok / error:<msg> */
    fun grantViaShizuku(ctx: Context, onAsync: (String) -> Unit): String {
        if (!shizukuInstalled(ctx)) return "not_installed"
        if (!shizukuRunning()) return "not_running"
        if (shizukuGranted()) return runShizuku(ctx)
        return try {
            val l = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQ) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    onAsync(if (grantResult == PackageManager.PERMISSION_GRANTED) runShizuku(ctx) else "denied")
                }
            }
            Shizuku.addRequestPermissionResultListener(l)
            Shizuku.requestPermission(REQ)
            "requested"
        } catch (t: Throwable) { "error:${t.message}" }
    }

    private fun runShizuku(ctx: Context): String = try {
        // newProcess 在 13.x 是 private，反射调用（Shizuku 官方仍保留该方法）
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("pm", "grant", ctx.packageName, "android.permission.WRITE_SECURE_SETTINGS"), null, null) as Process
        p.waitFor()
        afterGrant(ctx, p.errorStream.bufferedReader().readText())
    } catch (t: Throwable) { "error:${t.message}" }

    // ---------- Root ----------
    fun rootAvailable() = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")
        .any { File(it).exists() } || (System.getenv("PATH") ?: "").split(':').any { File(it, "su").exists() }

    fun grantViaRoot(ctx: Context): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"))
        p.waitFor()
        afterGrant(ctx, p.errorStream.bufferedReader().readText())
    } catch (t: Throwable) { "error:${t.message}" }

    private fun afterGrant(ctx: Context, err: String): String {
        return if (canWriteSecure(ctx)) { ensure(ctx, "granted"); "ok" } else "error:${err.ifBlank { "授权未生效" }.take(200)}"
    }

    /** 打开官方下载页；绝不把 Shizuku 打进本 APK（它是独立签名的特权进程）。 */
    fun openDownload(ctx: Context): String {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases")),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api")),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")),
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return "ok"
            } catch (_: Throwable) {}
        }
        return "error"
    }

    fun status(ctx: Context): Map<String, Any> = mapOf(
        "permanent" to canWriteSecure(ctx),
        "enabled" to (ToolboxAccessibilityService.instance != null || enabledInSettings(ctx)),
        "shizukuInstalled" to shizukuInstalled(ctx),
        "shizukuRunning" to shizukuRunning(),
        "root" to rootAvailable(),
        "adb" to adbCommand(ctx),
    )
}

class A11yBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        A11yKeeper.ensure(context, intent?.action ?: "boot")
    }
}
