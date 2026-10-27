package com.parallelspace.parallel_space

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri

/**
 * 厂商（ROM）适配集中处理：识别、桌面快捷方式、自启动 / 后台、电池优化、权限页跳转。
 *
 * 跳转一律不预检 resolveActivity（Android 11+ 包可见性会误判），逐个 try/catch，
 * 最后落到标准系统页和应用详情。Activity 上下文优先不加 NEW_TASK，避免 HyperOS
 * 把设置页丢到后台。
 */
object OemAdapter {
    private const val TAG = "OemAdapter"

    enum class Family { XIAOMI, HUAWEI, HONOR, OPPO, VIVO, SAMSUNG, MEIZU, STOCK }

    /** 权限页跳转结果。 */
    data class OpenResult(
        val code: String,
        val hint: String = "",
    )

    @SuppressLint("PrivateApi")
    fun prop(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
    private val brand = Build.BRAND.orEmpty().lowercase()
    private fun maker(vararg n: String) = n.any { manufacturer.contains(it) || brand.contains(it) }

    private val isHarmony: Boolean by lazy {
        try {
            val c = Class.forName("com.huawei.system.BuildEx")
            "harmony".equals(c.getMethod("getOsBrand").invoke(null) as? String, ignoreCase = true)
        } catch (_: Throwable) {
            prop("hw_sc.build.platform.version").isNotEmpty() ||
                prop("ro.build.version.harmony").isNotEmpty()
        }
    }

    /** 是否可识别为纯血鸿蒙 NEXT（不能跑 Android APK）。 */
    val isHarmonyNext: Boolean by lazy {
        isHarmony && (
            prop("ro.product.os.dist.name").contains("next", true) ||
                prop("ro.build.display.id").contains("NEXT", true) ||
                prop("hw_sc.build.os.apiversion").let { it.isNotEmpty() && !it.startsWith("3") && Build.VERSION.SDK_INT == 0 }
            )
    }

    /** 细分 ROM 标识。 */
    val rom: String by lazy {
        when {
            prop("ro.mi.os.version.name").isNotEmpty() || prop("ro.mi.os.version.code").isNotEmpty() -> "hyperos"
            prop("ro.miui.ui.version.name").isNotEmpty() -> "miui"
            maker("meizu") || prop("ro.build.display.id").contains("Flyme", true) ||
                prop("ro.flyme.version.id").isNotEmpty() -> "flyme"
            maker("honor") || prop("ro.build.version.magic").isNotEmpty() ||
                prop("ro.honor.build.display.id").isNotEmpty() -> "magicos"
            maker("huawei") && isHarmony -> "harmony"
            maker("huawei") || prop("ro.build.version.emui").isNotEmpty() -> "emui"
            maker("oneplus") -> "oxygenos"
            maker("realme") -> "realmeui"
            maker("oppo") || prop("ro.build.version.opporom").isNotEmpty() ||
                prop("ro.build.version.oplusrom").isNotEmpty() -> "coloros"
            maker("vivo", "iqoo") -> if (
                prop("ro.vivo.os.build.display.id").contains("OriginOS", true) ||
                prop("ro.vivo.os.name").contains("Origin", true) ||
                prop("ro.vivo.os.version").contains("Origin", true)
            ) "originos" else "funtouch"
            maker("samsung") -> "oneui"
            maker("google") || Build.MODEL.orEmpty().lowercase().contains("pixel") -> "pixel"
            else -> "stock"
        }
    }

    val family: Family by lazy {
        when (rom) {
            "hyperos", "miui" -> Family.XIAOMI
            "harmony", "emui" -> Family.HUAWEI
            "magicos" -> Family.HONOR
            "coloros", "oxygenos", "realmeui" -> Family.OPPO
            "originos", "funtouch" -> Family.VIVO
            "oneui" -> Family.SAMSUNG
            "flyme" -> Family.MEIZU
            else -> Family.STOCK
        }
    }

    /** 可读的 ROM 版本号（尽量从系统属性取）。 */
    val romVersion: String by lazy {
        when (rom) {
            "hyperos" -> prop("ro.mi.os.version.name").ifBlank { prop("ro.mi.os.version.code") }
            "miui" -> prop("ro.miui.ui.version.name")
            "harmony" -> prop("hw_sc.build.platform.version").ifBlank { prop("ro.build.version.harmony") }
            "emui" -> prop("ro.build.version.emui")
            "magicos" -> prop("ro.build.version.magic").ifBlank { prop("ro.honor.build.display.id") }
            "coloros" -> prop("ro.build.version.opporom").ifBlank { prop("ro.build.version.oplusrom") }
            "oxygenos" -> prop("ro.build.version.oplusrom").ifBlank { prop("ro.oxygen.version") }
            "realmeui" -> prop("ro.build.version.opporom").ifBlank { prop("ro.build.version.realmeui") }
            "originos", "funtouch" -> prop("ro.vivo.os.version").ifBlank {
                prop("ro.vivo.os.build.display.id")
            }
            "oneui" -> prop("ro.build.version.oneui").ifBlank { prop("ro.build.display.id") }
            "flyme" -> prop("ro.flyme.version.id").ifBlank { prop("ro.build.display.id") }
            else -> Build.VERSION.RELEASE.orEmpty()
        }.trim()
    }

    val label: String
        get() = when (rom) {
            "hyperos" -> "小米 HyperOS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "miui" -> "小米 MIUI" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "harmony" -> "华为 HarmonyOS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "emui" -> "华为 EMUI" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "magicos" -> "荣耀 MagicOS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "coloros" -> "OPPO ColorOS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "oxygenos" -> "一加 OxygenOS / ColorOS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "realmeui" -> "realme UI" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "originos" -> "vivo OriginOS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "funtouch" -> "vivo Funtouch OS" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "oneui" -> "三星 One UI" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "flyme" -> "魅族 Flyme" + romVersion.let { if (it.isBlank()) "" else " $it" }
            "pixel" -> "Google Pixel"
            else -> "原生 Android"
        }

    val brandLabel: String
        get() = when (family) {
            Family.XIAOMI -> when {
                brand.contains("redmi") -> "Redmi"
                brand.contains("poco") -> "POCO"
                else -> "小米"
            }
            Family.HUAWEI -> "华为"
            Family.HONOR -> "荣耀"
            Family.OPPO -> when {
                brand.contains("oneplus") || manufacturer.contains("oneplus") -> "一加"
                brand.contains("realme") -> "realme"
                else -> "OPPO"
            }
            Family.VIVO -> if (brand.contains("iqoo") || manufacturer.contains("iqoo")) "iQOO" else "vivo"
            Family.SAMSUNG -> "三星"
            Family.MEIZU -> "魅族"
            Family.STOCK -> if (rom == "pixel") "Google" else Build.BRAND.orEmpty().ifBlank { "未知" }
        }

    /** 国产 ROM 默认会拦截第三方桌面快捷方式，需要用户在权限页开启。 */
    val shortcutNeedsPermission get() = family in setOf(
        Family.XIAOMI, Family.HUAWEI, Family.HONOR, Family.OPPO, Family.VIVO, Family.MEIZU,
    )

    /** 旧式 INSTALL_SHORTCUT 广播仍被这些启动器接受。 */
    val supportsLegacyShortcut get() = family in setOf(
        Family.XIAOMI, Family.HUAWEI, Family.HONOR, Family.VIVO, Family.OPPO, Family.MEIZU,
    )

    /** 系统自带“应用双开 / 分身”所用的 userId（不是工作资料，必须排除）。 */
    val oemCloneUserIds = setOf(95 /* 三星双开 */, 128 /* 华为应用分身 */, 666 /* vivo */, 999 /* 小米 / OPPO */)

    /**
     * 桌面快捷方式权限：true 允许 / false 拒绝 / null 无法判断。
     * 目前只有 MIUI / HyperOS 能可靠读取（AppOps 10017）。
     */
    fun shortcutPermission(ctx: Context): Boolean? {
        if (family != Family.XIAOMI) return null
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            (m.invoke(ops, 10017, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- 跳转核心 ----------------
    private fun cn(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun details(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())

    /**
     * 依次尝试。返回 rom / details / settings / error。
     * Activity 优先不加 NEW_TASK；失败再带 NEW_TASK 重试一次。
     */
    fun launchFirst(act: Activity, intents: List<Intent>): String {
        for (i in intents) {
            if (tryStart(act, i)) return "rom"
        }
        if (tryStart(act, details(act))) return "details"
        return if (tryStart(act, Intent(Settings.ACTION_SETTINGS))) "settings" else "error"
    }

    fun launchFirstDetailed(act: Activity, intents: List<Intent>, kind: String): OpenResult {
        val code = launchFirst(act, intents)
        if (code != "error") return OpenResult(code)
        return OpenResult("error", manualHint(kind))
    }

    /**
     * 不先卡 resolveActivity：Android 11+ 的包可见性会让厂商页解析失败，但 startActivity 仍然能打开。
     */
    private fun tryStart(act: Activity, i: Intent): Boolean {
        // 1) Activity 上下文：不加 NEW_TASK，避免 HyperOS 把设置丢到后台
        try {
            act.startActivity(Intent(i))
            return true
        } catch (t: Throwable) {
            Log.d(TAG, "start without NEW_TASK failed: ${i.component ?: i.action}: ${t.message}")
        }
        // 2) 带 NEW_TASK 再试（部分厂商页要求）
        try {
            act.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (t: Throwable) {
            Log.d(TAG, "start with NEW_TASK failed: ${i.component ?: i.action}: ${t.message}")
            return false
        }
    }

    /** 各品牌手动路径提示（全部跳转失败时展示）。 */
    fun manualHint(kind: String): String {
        val where = when (family to kind) {
            Family.XIAOMI to "overlay" -> "设置 → 应用设置 → 应用管理 → 平行空间 → 权限管理 → 显示悬浮窗"
            Family.XIAOMI to "accessibility" -> "设置 → 更多设置 → 无障碍 → 已下载的应用 / 已安装的服务 → 平行空间"
            Family.XIAOMI to "autostart" -> "设置 → 应用设置 → 授权管理 → 自启动管理 → 允许平行空间"
            Family.XIAOMI to "battery" -> "设置 → 电池 → 应用电量管理管理 → 平行空间 → 无限制"
            Family.XIAOMI to "shortcut" -> "设置 → 应用设置 → 应用管理 → 平行空间 → 权限管理 → 桌面快捷方式"
            Family.HUAWEI to "overlay", Family.HONOR to "overlay" -> "设置 → 应用和服务 → 应用管理 → 平行空间 → 权限 → 悬浮窗"
            Family.HUAWEI to "accessibility", Family.HONOR to "accessibility" -> "设置 → 辅助功能 → 无障碍 → 平行空间"
            Family.HUAWEI to "autostart", Family.HONOR to "autostart" -> "设置 → 应用和服务 → 应用启动管理 → 平行空间 → 手动管理并全部允许"
            Family.OPPO to "overlay" -> "设置 → 应用 → 应用管理 → 平行空间 → 权限 → 悬浮窗"
            Family.OPPO to "accessibility" -> "设置 → 其他设置 → 无障碍 → 平行空间"
            Family.OPPO to "autostart" -> "设置 → 应用 → 自启动管理 → 允许平行空间"
            Family.VIVO to "overlay" -> "设置 → 应用与权限 → 权限管理 → 权限 → 悬浮窗 → 平行空间"
            Family.VIVO to "accessibility" -> "设置 → 快捷与辅助 → 无障碍 → 平行空间"
            Family.VIVO to "autostart" -> "设置 → 电池 → 后台耗电管理 / 权限 → 自启动 → 平行空间"
            Family.SAMSUNG to "overlay" -> "设置 → 应用 → 平行空间 → 在其他应用上层显示"
            Family.SAMSUNG to "accessibility" -> "设置 → 辅助功能 → 已安装的应用 → 平行空间"
            Family.SAMSUNG to "battery" -> "设置 → 电池和设备维护 → 电池 → 后台使用限制 → 永不休眠的应用"
            Family.MEIZU to "overlay" -> "设置 → 应用管理 → 平行空间 → 权限管理 → 悬浮窗"
            Family.MEIZU to "accessibility" -> "设置 → 辅助功能 → 无障碍 → 平行空间"
            else -> when (kind) {
                "overlay" -> "设置 → 应用 → 特殊应用权限 → 显示在其他应用的上层 → 平行空间"
                "accessibility" -> "设置 → 无障碍 → 已下载的应用 / 已安装的服务 → 平行空间"
                "battery" -> "设置 → 应用 → 平行空间 → 电池 → 不优化"
                "notification" -> "设置 → 应用 → 平行空间 → 通知"
                "shortcut" -> "设置 → 应用 → 平行空间 → 权限"
                else -> "设置 → 应用 → 平行空间"
            }
        }
        return "没能自动打开设置页。请手动：$where"
    }

    // ---------------- Intent 表（可单测） ----------------

    /**
     * 返回某类权限跳转的有序步骤标签（不含 Context）。
     * 每条链必须以 standard / details / settings 一类通用回退结尾。
     * 供单元测试与调试使用。
     */
    fun chainLabels(kind: String, fam: Family = family): List<String> = when (kind) {
        "overlay" -> overlayChainLabels(fam)
        "accessibility" -> accessibilityChainLabels(fam)
        "autostart" -> autoStartChainLabels(fam)
        "shortcut" -> shortcutChainLabels(fam)
        "notification" -> listOf("ACTION_APP_NOTIFICATION_SETTINGS", "APPLICATION_DETAILS", "SETTINGS")
        "battery" -> listOf(
            "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            "APPLICATION_DETAILS",
            "SETTINGS",
        )
        else -> listOf("APPLICATION_DETAILS", "SETTINGS")
    }

    fun overlayChainLabels(fam: Family = family): List<String> {
        val oem = when (fam) {
            Family.XIAOMI -> listOf(
                "miui:PermissionsEditorActivity",
                "miui:AppPermissionsEditorActivity",
                "miui.intent.action.APP_PERM_EDITOR",
                "miui:AddViewWindowActivity",
            )
            Family.HUAWEI -> listOf(
                "huawei:AddViewMonitorActivity",
                "huawei:NotificationManagerActivity",
                "huawei:MainActivity(permission)",
            )
            Family.HONOR -> listOf(
                "honor:AddViewMonitorActivity",
                "honor:MainActivity(permission)",
                "huawei:AddViewMonitorActivity",
            )
            Family.OPPO -> listOf(
                "coloros:FloatWindowListActivity",
                "oplus:FloatWindowListActivity",
                "coloros:sysfloatwindow.FloatWindowListActivity",
                "oppo.safe:FloatWindowListActivity",
            )
            Family.VIVO -> listOf(
                "vivo:SoftPermissionDetailActivity",
                "iqoo:FloatWindowManager",
                "vivo:PurviewTabActivity",
            )
            Family.SAMSUNG -> listOf(
                "samsung:SmartManagerDashBoardActivity",
                "samsung.lool:BatteryActivity",
            )
            Family.MEIZU -> listOf(
                "meizu:AppSecurityActivity",
                "meizu:PermissionActivity",
            )
            Family.STOCK -> emptyList()
        }
        return oem + listOf(
            "ACTION_MANAGE_OVERLAY_PERMISSION:package",
            "ACTION_MANAGE_OVERLAY_PERMISSION",
            "APPLICATION_DETAILS",
            "SETTINGS",
        )
    }

    fun accessibilityChainLabels(fam: Family = family): List<String> {
        val oem = when (fam) {
            Family.XIAOMI -> listOf("miui:PrivacySettingsActivity", "ACCESSIBILITY_DETAILS_SETTINGS")
            Family.HUAWEI -> listOf("huawei:AccessibilityActivity", "ACCESSIBILITY_DETAILS_SETTINGS")
            Family.HONOR -> listOf("honor:AccessibilityActivity", "ACCESSIBILITY_DETAILS_SETTINGS")
            else -> listOf("ACCESSIBILITY_DETAILS_SETTINGS")
        }
        return oem + listOf("ACTION_ACCESSIBILITY_SETTINGS", "APPLICATION_DETAILS", "SETTINGS")
    }

    fun autoStartChainLabels(fam: Family = family): List<String> {
        val oem = when (fam) {
            Family.XIAOMI -> listOf("miui:AutoStartManagementActivity", "miui:HiddenAppsConfigActivity")
            Family.HUAWEI -> listOf(
                "huawei:StartupNormalAppListActivity",
                "huawei:ProtectActivity",
                "huawei:StartupAppControlActivity",
            )
            Family.HONOR -> listOf("honor:StartupNormalAppListActivity", "huawei:StartupNormalAppListActivity")
            Family.OPPO -> listOf(
                "coloros:StartupAppListActivity",
                "coloros:permission.startup.StartupAppListActivity",
                "oplus:PowerUsageModelActivity",
                "oppo.safe:StartupAppListActivity",
            )
            Family.VIVO -> listOf(
                "vivo:BgStartUpManagerActivity",
                "iqoo:BgStartUpManager",
                "iqoo:AddWhiteListActivity",
            )
            Family.SAMSUNG -> listOf("samsung.lool:BatteryActivity", "samsung.sm:BatteryActivity")
            Family.MEIZU -> listOf("meizu:SmartPowerActivity", "meizu:AppSecurityActivity")
            Family.STOCK -> emptyList()
        }
        return oem + listOf("APPLICATION_DETAILS", "SETTINGS")
    }

    fun shortcutChainLabels(fam: Family = family): List<String> {
        val oem = when (fam) {
            Family.XIAOMI -> listOf(
                "miui:PermissionsEditorActivity",
                "miui:AppPermissionsEditorActivity",
                "miui.intent.action.APP_PERM_EDITOR",
            )
            Family.HUAWEI -> listOf("huawei:SingleAppActivity", "huawei:MainActivity(permission)")
            Family.HONOR -> listOf("honor:SingleAppActivity", "honor:MainActivity(permission)")
            Family.OPPO -> listOf(
                "oplus:PermissionManagerActivity",
                "coloros:PermissionManagerActivity",
                "coloros:PermissionSinglePageActivity",
            )
            Family.VIVO -> listOf("vivo:SoftPermissionDetailActivity", "iqoo:SoftPermissionDetailActivity")
            Family.MEIZU -> listOf("meizu:AppSecurityActivity")
            else -> emptyList()
        }
        return oem + listOf("APPLICATION_DETAILS", "SETTINGS")
    }

    /** 所有主流品牌都必须有完整链，供测试遍历。 */
    fun allFamilies(): List<Family> = Family.entries.toList()

    fun shortcutPermissionIntents(ctx: Context): List<Intent> {
        val p = ctx.packageName
        return when (family) {
            Family.XIAOMI -> listOf(
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", p),
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    .putExtra("extra_pkgname", p),
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .setAction("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", p),
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", p),
            )
            Family.HUAWEI -> listOf(
                cn("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.SingleAppActivity").putExtra("packageName", p),
                cn("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity"),
            )
            Family.HONOR -> listOf(
                cn("com.hihonor.systemmanager", "com.hihonor.permissionmanager.ui.SingleAppActivity").putExtra("packageName", p),
                cn("com.hihonor.systemmanager", "com.hihonor.permissionmanager.ui.MainActivity"),
                cn("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity"),
            )
            Family.OPPO -> listOf(
                cn("com.oplus.safecenter", "com.oplus.safecenter.permission.PermissionManagerActivity").putExtra("packageName", p),
                cn("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity").putExtra("packageName", p),
                cn("com.coloros.safecenter", "com.coloros.safecenter.permission.singlepage.PermissionSinglePageActivity").putExtra("packageName", p),
                cn("com.oppo.safe", "com.oppo.safe.permission.PermissionAppListActivity"),
            )
            Family.VIVO -> listOf(
                cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    .putExtra("packagename", p),
                cn("com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity").putExtra("packagename", p),
                cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            )
            Family.MEIZU -> listOf(
                cn("com.meizu.safe", "com.meizu.safe.security.AppSecActivity").putExtra("packageName", p),
                cn("com.meizu.safe", "com.meizu.safe.permission.PermissionActivity"),
            )
            else -> emptyList()
        }
    }

    fun autoStartIntents(ctx: Context): List<Intent> = when (family) {
        Family.XIAOMI -> listOf(
            cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            cn("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", ctx.packageName).putExtra("package_label", "平行空间"),
        )
        Family.HUAWEI -> listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        Family.HONOR -> listOf(
            cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Family.OPPO -> listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        Family.VIVO -> listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Family.SAMSUNG -> listOf(
            cn("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            cn("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        )
        Family.MEIZU -> listOf(
            cn("com.meizu.safe", "com.meizu.safe.powerui.PowerAppPermissionActivity"),
            cn("com.meizu.safe", "com.meizu.safe.security.AppSecActivity"),
        )
        Family.STOCK -> emptyList()
    }

    fun hasAutoStartPage(ctx: Context) = autoStartIntents(ctx).isNotEmpty()

    // ---------------- 电池优化 ----------------
    fun isIgnoringBattery(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBattery(act: Activity): String {
        if (isIgnoringBattery(act)) return "ok"
        val req = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${act.packageName}".toUri())
        if (tryStart(act, req)) return "requested"
        return if (launchFirst(act, listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) != "error") {
            "requested"
        } else {
            "error"
        }
    }

    // ---------------- 工作资料说明 ----------------
    val workProfileNote: String
        get() = when (family) {
            Family.XIAOMI -> "系统的「应用双开」「手机分身」不是工作资料，平行空间不会使用它们；部分 MIUI / HyperOS 版本会拦截第三方创建工作资料，失败时请更新系统或改用系统双开。"
            Family.HUAWEI -> "EMUI / HarmonyOS 的「应用分身」「隐私空间」与工作资料不同；部分版本不允许第三方应用创建工作资料。HarmonyOS NEXT（纯血鸿蒙）不支持 Android 应用。"
            Family.HONOR -> "MagicOS 的「应用分身」「隐私空间」与工作资料不同；部分版本可能拦截第三方创建工作资料。"
            Family.OPPO -> "ColorOS / OxygenOS / realme UI 的「应用分身」「系统分身」不是工作资料，通常可以共存。"
            Family.VIVO -> "OriginOS / Funtouch 的「应用分身」不是工作资料；部分版本会禁止第三方创建工作资料。"
            Family.SAMSUNG -> "三星「安全文件夹」（Knox）本身就是一个工作资料，已启用时无法再创建；「双开应用」不受影响。"
            Family.MEIZU -> "Flyme 的「应用分身」不是工作资料；请以虚拟引擎为主路径。"
            Family.STOCK -> if (rom == "pixel") {
                "Pixel 使用系统工作资料。平行空间的主路径仍然是进程内虚拟引擎。"
            } else {
                ""
            }
        }

    // ---------------- 机型适配检查 ----------------

    data class Check(
        val id: String,
        val title: String,
        val status: String,
        val detail: String,
        val canOpen: Boolean,
    )

    /** Settings 结果；HyperOS 在 IME/焦点切换时偶发瞬假。 */
    fun settingsCanDrawOverlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    /** AppOps SYSTEM_ALERT_WINDOW；与 Settings 交叉验证。 */
    fun overlayAppOpsAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(),
                ctx.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(),
                ctx.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    /**
     * 任一来源为真即视为有权限（偏向保活，避免 HyperOS 瞬假拆服务）。
     * 真正要停服务时请用 [overlayClearlyDenied]。
     */
    fun canDrawOverlay(ctx: Context): Boolean =
        settingsCanDrawOverlay(ctx) || overlayAppOpsAllowed(ctx)

    /** Settings 与 AppOps 都否定时才算真正丢了悬浮窗权限。 */
    fun overlayClearlyDenied(ctx: Context): Boolean =
        !settingsCanDrawOverlay(ctx) && !overlayAppOpsAllowed(ctx)

    fun accessibilityOn(ctx: Context): Boolean {
        if (ToolboxAccessibilityService.instance != null) return true
        return try {
            val cn = ComponentName(ctx, ToolboxAccessibilityService::class.java)
            val flat = cn.flattenToString()
            val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (raw.split(':').any { it.equals(flat, true) }) return true
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo?.name == ToolboxAccessibilityService::class.java.name }
        } catch (_: Throwable) {
            false
        }
    }

    fun notificationGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun report(ctx: Context): Map<String, Any?> = mapOf(
        "rom" to rom,
        "romLabel" to label,
        "romVersion" to romVersion,
        "brand" to brandLabel,
        "family" to family.name,
        "manufacturer" to Build.MANUFACTURER.orEmpty(),
        "model" to Build.MODEL.orEmpty(),
        "sdk" to Build.VERSION.SDK_INT,
        "release" to Build.VERSION.RELEASE.orEmpty(),
        "abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        "harmonyNext" to isHarmonyNext,
        "overlayHint" to manualHint("overlay"),
        "accessibilityHint" to manualHint("accessibility"),
        "items" to checks(ctx).map {
            mapOf(
                "id" to it.id,
                "title" to it.title,
                "status" to it.status,
                "detail" to it.detail,
                "canOpen" to it.canOpen,
            )
        },
    )

    fun checks(ctx: Context): List<Check> = listOf(
        overlayCheck(ctx),
        accessibilityCheck(ctx),
        notificationCheck(ctx),
        batteryCheck(ctx),
        backgroundCheck(ctx),
        shortcutCheck(ctx),
        fgsCheck(ctx),
        processCheck(ctx),
        abiCheck(),
        platformCheck(),
    )

    /** 打开对应设置页。每条路径都在 [launchFirst] 里逐个 try。失败时附带手动路径。 */
    fun openCheck(act: Activity, id: String): String {
        val r = when (id) {
            "overlay" -> launchFirstDetailed(act, overlayIntents(act), "overlay")
            "accessibility" -> {
                ToolboxController.armAccessibility(act)
                launchFirstDetailed(act, accessibilityIntents(act), "accessibility")
            }
            "notification" -> launchFirstDetailed(act, notificationIntents(act), "notification")
            "battery" -> {
                val code = requestIgnoreBattery(act)
                if (code == "error") OpenResult("error", manualHint("battery")) else OpenResult(code)
            }
            "autostart" -> launchFirstDetailed(act, autoStartIntents(act), "autostart")
            "shortcut" -> launchFirstDetailed(
                act,
                shortcutPermissionIntents(act).ifEmpty { listOf(details(act)) },
                "shortcut",
            )
            "lock" -> launchFirstDetailed(act, listOf(Intent(Settings.ACTION_SECURITY_SETTINGS)), "lock")
            else -> OpenResult("none")
        }
        // Flutter 侧用字符串；错误时用 "error|<hint>" 传手动路径
        return if (r.code == "error" && r.hint.isNotBlank()) "error|${r.hint}" else r.code
    }

    private fun overlayCheck(ctx: Context): Check {
        val on = canDrawOverlay(ctx)
        val where = when (family) {
            Family.XIAOMI -> "HyperOS / MIUI：权限管理里打开「显示悬浮窗」。"
            Family.HUAWEI -> "HarmonyOS / EMUI：允许「悬浮窗」或「在其他应用上层显示」。"
            Family.HONOR -> "MagicOS：允许「悬浮窗」。找不到时会回到应用详情。"
            Family.OPPO -> "ColorOS：权限里打开「悬浮窗」。"
            Family.VIVO -> "OriginOS：权限里打开「悬浮窗」。"
            Family.SAMSUNG -> "One UI：使用系统的「显示在其他应用的上层」。"
            Family.MEIZU -> "Flyme：权限管理里允许「悬浮窗」。"
            Family.STOCK -> if (rom == "pixel") "Pixel：使用系统的「显示在其他应用的上层」。" else "使用系统的悬浮窗权限页。"
        }
        return Check("overlay", "悬浮窗", if (on) "ok" else "off", where + if (on) " 已允许。" else " 尚未允许，工具箱小窗不会出现。", true)
    }

    private fun accessibilityCheck(ctx: Context): Check {
        val on = accessibilityOn(ctx)
        val killed = ToolboxController.accessibilityLikelyKilled(ctx)
        val status = when {
            on -> "ok"
            killed -> "warn"
            else -> "off"
        }
        val detail = when {
            on -> "已开启。只有你点「填入」时，才会把用户名和密码写入当前输入框。"
            killed -> "系统可能把无障碍服务关掉了。一键填入会改成复制到剪贴板。可以重新开启。"
            else -> "未开启时，填入会复制到剪贴板。请开启「平行空间」无障碍服务后即可一键填入。"
        }
        return Check("accessibility", "无障碍填入", status, detail, true)
    }

    private fun notificationCheck(ctx: Context): Check {
        if (Build.VERSION.SDK_INT < 33) {
            return Check("notification", "通知", "ok", "Android 13 以下在安装时授予通知权限。", true)
        }
        val on = notificationGranted(ctx)
        return Check(
            "notification",
            "通知",
            if (on) "ok" else "off",
            if (on) "已允许。前台服务和权限提醒可以显示。" else "Android 13 起需要通知权限，否则工具箱和虚拟定位的前台通知会被系统藏起来。",
            true,
        )
    }

    private fun batteryCheck(ctx: Context): Check {
        val on = isIgnoringBattery(ctx)
        val extra = when (family) {
            Family.XIAOMI -> " HyperOS 还要在电池策略里选「无限制」。"
            Family.HUAWEI, Family.HONOR -> " 华为 / 荣耀还要允许后台活动。"
            Family.OPPO, Family.VIVO -> " ColorOS / OriginOS 建议同时允许后台运行。"
            Family.SAMSUNG -> " One UI 可在「不受监视的应用程序」里加入平行空间。"
            Family.MEIZU -> " Flyme 建议允许后台运行。"
            Family.STOCK -> ""
        }
        return Check(
            "battery",
            "电池优化白名单",
            if (on) "ok" else "warn",
            (if (on) "已忽略电池优化。" else "尚未忽略电池优化，后台的虚拟定位和悬浮窗可能被清掉。") + extra,
            true,
        )
    }

    private fun backgroundCheck(ctx: Context): Check {
        val restricted = try {
            if (Build.VERSION.SDK_INT >= 28) {
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
        if (family == Family.STOCK) {
            return Check(
                "autostart",
                "自启动与后台",
                if (restricted) "warn" else "ok",
                if (rom == "pixel") {
                    "Pixel 没有单独的自启动开关。" + if (restricted) " 系统正在限制后台。" else " 后台没有被额外限制。"
                } else {
                    "原生 Android 没有单独的自启动开关。" + if (restricted) " 系统正在限制后台。" else ""
                },
                false,
            )
        }
        val status = if (restricted) "warn" else "unknown"
        val detail = when (family) {
            Family.XIAOMI -> "无法直接读取自启动开关。请在 HyperOS / MIUI 的自启动管理里允许平行空间。"
            Family.HUAWEI -> "HarmonyOS / EMUI 请在应用启动管理里改为手动管理，并允许后台活动。"
            Family.HONOR -> "MagicOS 请在应用启动管理里允许自启动和后台活动。"
            Family.OPPO -> "ColorOS 请在自启动管理和后台耗电里允许平行空间。"
            Family.VIVO -> "OriginOS 请在「后台弹出」和自启动里允许平行空间。权限页打不开时会回到应用详情。"
            Family.SAMSUNG -> "One UI 请在电池里把平行空间设为不受限。"
            Family.MEIZU -> "Flyme 请在后台管理里允许平行空间。"
            Family.STOCK -> ""
        } + if (restricted) " 系统报告后台已被限制。" else ""
        return Check("autostart", "自启动与后台", status, detail, true)
    }

    private fun shortcutCheck(ctx: Context): Check {
        val perm = shortcutPermission(ctx)
        val status = when (perm) {
            true -> "ok"
            false -> "off"
            null -> if (shortcutNeedsPermission) "unknown" else "ok"
        }
        val detail = when (family) {
            Family.XIAOMI -> "MIUI / HyperOS 用 AppOps 10017 判断「桌面快捷方式」。未允许时，添加分身到桌面会失败。"
            Family.HUAWEI -> "HarmonyOS / EMUI 需要在权限里允许创建桌面快捷方式。系统没有稳定的读取接口。"
            Family.HONOR -> "MagicOS 需要允许创建桌面快捷方式。"
            Family.OPPO -> "ColorOS 请在权限页允许「桌面快捷方式」。系统没有稳定的读取接口。"
            Family.VIVO -> "OriginOS 请在权限页允许「桌面快捷方式」。系统没有稳定的读取接口。"
            Family.SAMSUNG -> "One UI 一般可以直接固定快捷方式。"
            Family.MEIZU -> "Flyme 需要允许创建桌面快捷方式。"
            Family.STOCK -> "当前系统一般可以直接添加桌面快捷方式。"
        }
        return Check("shortcut", "桌面快捷方式", status, detail, shortcutNeedsPermission || perm == false)
    }

    private fun fgsCheck(ctx: Context): Check {
        if (Build.VERSION.SDK_INT < 34) {
            return Check("fgs", "前台服务类型", "ok", "Android 14 以下不强制声明前台服务类型。", false)
        }
        val required = listOf(
            "com.parallelspace.parallel_space.MockLocationService",
            "com.parallelspace.parallel_space.ToolboxOverlayService",
            "top.niunaijun.blackbox.core.system.DaemonService",
        )
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES)
            val missing = required.filter { name ->
                val s = info.services?.firstOrNull { it.name == name } ?: return@filter true
                (s.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) == 0
            }
            if (missing.isEmpty()) {
                Check("fgs", "前台服务类型", "ok", "虚拟定位、工具箱和虚拟引擎守护进程都声明了 specialUse，并带有用途说明。", false)
            } else {
                Check("fgs", "前台服务类型", "warn", "这些服务缺少 specialUse：" + missing.joinToString("、"), false)
            }
        } catch (_: Throwable) {
            Check("fgs", "前台服务类型", "unknown", "无法读取前台服务声明。", false)
        }
    }

    private fun processCheck(ctx: Context): Check {
        val name = try {
            ProcessNameCompat.get(ctx)
        } catch (_: Throwable) {
            ""
        }
        return if (name.isNotBlank()) {
            Check(
                "process",
                "虚拟引擎进程名",
                "ok",
                "已用 ProcessNameCompat 补丁。HyperOS 在启动早期拿不到 ActivityManager 进程列表时，仍然能得到进程名（当前：$name）。",
                false,
            )
        } else {
            Check("process", "虚拟引擎进程名", "warn", "没有得到进程名，虚拟引擎在 HyperOS 上可能无法初始化。", false)
        }
    }

    private fun abiCheck(): Check {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Process.is64Bit()
        return if (is64) {
            Check("abi", "64 位", "ok", "当前进程是 64 位（$abi）。发布包只包含 arm64-v8a。", false)
        } else {
            Check("abi", "64 位", "warn", "当前不是 64 位进程（$abi）。发布包不提供 32 位库，请安装 arm64 包。", false)
        }
    }

    private fun platformCheck(): Check {
        val sdk = Build.VERSION.SDK_INT
        val notes = ArrayList<String>()
        if (sdk >= 34) notes += "Android 14 起前台服务必须声明类型，本应用使用 specialUse"
        if (sdk >= 35) notes += "Android 15 限制从后台启动界面和前台服务，工具箱只在你打开分身时启动"
        if (sdk >= 36) notes += "Android 16 继续使用显式 Intent 和不可变 PendingIntent"
        val page = try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        } catch (_: Throwable) {
            0L
        }
        if (page >= 16384L) notes += "本机页大小为 ${page} 字节，虚拟引擎的 so 按 16KB 对齐"
        if (isHarmonyNext) notes += "检测到可能是 HarmonyOS NEXT，无法安装或运行 Android 应用"
        if (notes.isEmpty()) notes += "当前系统版本没有需要单独处理的行为变更"
        return Check("platform", "系统版本行为", "ok", "Android ${Build.VERSION.RELEASE}（API $sdk）。" + notes.joinToString("；") + "。", false)
    }

    fun overlayIntents(ctx: Context): List<Intent> {
        val p = ctx.packageName
        val standardPkg = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$p".toUri())
        val standardBare = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        val oem = when (family) {
            Family.XIAOMI -> listOf(
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", p),
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    .putExtra("extra_pkgname", p),
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", p),
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .setAction("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", p),
                // 部分 HyperOS 还有悬浮窗专用页
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.AddViewWindowActivity")
                    .putExtra("extra_pkgname", p),
            )
            Family.HUAWEI -> listOf(
                cn("com.huawei.systemmanager", "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity"),
                cn("com.huawei.systemmanager", "com.huawei.notificationmanager.ui.NotificationManagmentActivity"),
                cn("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity"),
            )
            Family.HONOR -> listOf(
                cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.addviewmonitor.AddViewMonitorActivity"),
                cn("com.hihonor.systemmanager", "com.hihonor.permissionmanager.ui.MainActivity"),
                cn("com.huawei.systemmanager", "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity"),
            )
            Family.OPPO -> listOf(
                cn("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"),
                cn("com.oplus.safecenter", "com.oplus.safecenter.sysfloatwindow.FloatWindowListActivity"),
                cn("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity"),
                cn("com.oppo.safe", "com.oppo.safe.permission.floatwindow.FloatWindowListActivity"),
            )
            Family.VIVO -> listOf(
                cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    .putExtra("packagename", p),
                cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.FloatWindowManager"),
                cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            )
            Family.SAMSUNG -> listOf(
                cn("com.samsung.android.sm", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"),
                cn("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            )
            Family.MEIZU -> listOf(
                cn("com.meizu.safe", "com.meizu.safe.security.AppSecActivity").putExtra("packageName", p),
                cn("com.meizu.safe", "com.meizu.safe.permission.PermissionActivity"),
            )
            Family.STOCK -> emptyList()
        }
        return oem + standardPkg + standardBare
    }

    fun accessibilityIntents(ctx: Context): List<Intent> {
        val component = ComponentName(ctx, ToolboxAccessibilityService::class.java)
        val detail = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString()),
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra("android.intent.extra.COMPONENT_NAME", component),
            )
        } else {
            emptyList()
        }
        val oem = when (family) {
            Family.XIAOMI -> listOf(
                cn("com.miui.securitycenter", "com.miui.permcenter.settings.PrivacySettingsActivity"),
            )
            Family.HUAWEI -> listOf(
                cn("com.huawei.systemmanager", "com.huawei.systemmanager.accessibility.AccessibilityActivity"),
            )
            Family.HONOR -> listOf(
                cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.accessibility.AccessibilityActivity"),
            )
            else -> emptyList()
        }
        return detail + oem + Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun notificationIntents(ctx: Context): List<Intent> {
        val p = ctx.packageName
        val modern = if (Build.VERSION.SDK_INT >= 26) {
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, p)
                    .putExtra("app_package", p)
                    .putExtra("app_uid", ctx.applicationInfo.uid),
            )
        } else {
            emptyList()
        }
        return modern + details(ctx)
    }
}
