package com.parallelspace.parallel_space

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings

/**
 * 无障碍「智能跳转」：尽量直接落到本应用无障碍服务的开关页。
 *
 * 顺序：
 * 1. ACCESSIBILITY_DETAILS_SETTINGS（Android 11+，带 EXTRA_COMPONENT_NAME）—— 直接是本服务开关页
 * 2. 品牌页（小米/HyperOS、华为、荣耀、OPPO/一加/realme、vivo/iQOO、三星、魅族、Pixel）
 * 3. 无障碍列表页，带 :settings:fragment_args_key 高亮本服务
 *
 * Android 13+ 侧载安装的应用，服务开关会是灰的，需要先在应用详情页右上角「⋮ → 允许受限制的设置」。
 * 检测到这种情况时先跳应用详情页，并返回 "restricted"，界面据此给出说明。
 */
object A11yJump {
    private const val OP_RESTRICTED = "android:access_restricted_settings"

    fun component(ctx: Context) = ComponentName(ctx, ToolboxAccessibilityService::class.java)

    /**
     * true = 很可能被「受限制的设置」拦着（服务开关会是灰色）；false = 不受限；null = 无法判断。
     */
    fun restricted(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(OP_RESTRICTED, Process.myUid(), ctx.packageName)
            // 侧载默认 MODE_ERRORED；用户点了「允许受限制的设置」后为 MODE_ALLOWED
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> false
                AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_IGNORED -> !installedFromStore(ctx)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun installedFromStore(ctx: Context): Boolean = try {
        val src = if (Build.VERSION.SDK_INT >= 30) {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }
        src != null && src in setOf(
            "com.android.vending", "com.xiaomi.market", "com.huawei.appmarket", "com.hihonor.appmarket",
            "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.sec.android.app.samsungapps",
            "com.meizu.mstore",
        )
    } catch (_: Throwable) {
        false
    }

    fun status(ctx: Context): Map<String, Any?> = mapOf(
        "on" to OemAdapter.accessibilityOn(ctx),
        "restricted" to (if (OemAdapter.accessibilityOn(ctx)) false else restricted(ctx)),
        "sdk" to Build.VERSION.SDK_INT,
        "rom" to OemAdapter.rom,
    )

    /** 按优先级排好的跳转候选。 */
    fun intents(ctx: Context): List<Intent> {
        val cn = component(ctx)
        val flat = cn.flattenToString()
        val out = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 30) {
            out += Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, flat)
            out += Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
        }
        out += brandIntents(flat)
        out += highlighted(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), flat)
        return out
    }

    private fun highlighted(i: Intent, flat: String): Intent {
        val args = Bundle().apply { putString(":settings:fragment_args_key", flat) }
        return i.putExtra(":settings:fragment_args_key", flat)
            .putExtra(":settings:show_fragment_args", args)
    }

    private fun cn(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun brandIntents(flat: String): List<Intent> = when (OemAdapter.family) {
        // HyperOS / MIUI：「已下载的应用」无障碍列表，带高亮
        OemAdapter.Family.XIAOMI -> listOf(
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity"), flat),
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"), flat),
        )
        OemAdapter.Family.HUAWEI -> listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.accessibility.AccessibilityActivity"),
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity"), flat),
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"), flat),
            // 部分 HarmonyOS 把已下载服务放在设置里的「已安装的服务」
            highlighted(cn("com.android.settings", "com.android.settings.accessibility.AccessibilitySettings"), flat),
        )
        OemAdapter.Family.HONOR -> listOf(
            cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.accessibility.AccessibilityActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.accessibility.AccessibilityActivity"),
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity"), flat),
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"), flat),
        )
        OemAdapter.Family.OPPO -> listOf(
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity"), flat),
            highlighted(cn("com.oplus.accessibility", "com.oplus.accessibility.service.AccessibilitySettingsActivity"), flat),
        )
        OemAdapter.Family.VIVO -> listOf(
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"), flat),
        )
        OemAdapter.Family.SAMSUNG -> listOf(
            highlighted(cn("com.samsung.accessibility", "com.samsung.accessibility.installedservice.InstalledServicesActivity"), flat),
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"), flat),
        )
        OemAdapter.Family.MEIZU -> listOf(
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"), flat),
        )
        // Pixel / 原生：「已下载的应用」页
        OemAdapter.Family.STOCK -> listOf(
            highlighted(cn("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity"), flat),
        )
    }

    private fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

    /**
     * 从任意上下文跳转（Activity 或 Service）。
     * @return "on" 已开启 | "restricted" 跳到了应用详情（需先允许受限制的设置）| "details" | "list" | "error"
     */
    fun open(ctx: Context, forceServicePage: Boolean = false): String {
        if (OemAdapter.accessibilityOn(ctx) && !forceServicePage) return "on"
        val activity = ctx as? android.app.Activity
        fun start(i: Intent): Boolean {
            if (activity != null) {
                try {
                    activity.startActivity(Intent(i))
                    return true
                } catch (_: Throwable) {
                }
            }
            return try {
                ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (!forceServicePage && restricted(ctx) == true && start(appDetails(ctx))) {
            ToolboxCrashLog.append(ctx, "INFO", "a11y jump -> app details (restricted settings)")
            return "restricted"
        }
        ToolboxController.armAccessibility(ctx)
        val list = intents(ctx)
        for ((idx, i) in list.withIndex()) {
            if (start(i)) {
                val code = if (idx <= 1 && Build.VERSION.SDK_INT >= 30) "details" else "list"
                ToolboxCrashLog.append(ctx, "INFO", "a11y jump ok via=${i.component ?: i.action} code=$code")
                return code
            }
        }
        return if (start(appDetails(ctx))) "list" else "error"
    }
}
