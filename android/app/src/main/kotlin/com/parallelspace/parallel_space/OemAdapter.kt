package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 厂商（ROM）适配集中处理：识别、桌面快捷方式权限、自启动 / 后台运行、电池优化、工作资料限制说明。
 * 所有跳转都先 resolveActivity 再 try/catch，逐个回退，最后回到“应用详情”。
 */
object OemAdapter {
    enum class Family { XIAOMI, HUAWEI, HONOR, OPPO, VIVO, SAMSUNG, STOCK }

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
            prop("hw_sc.build.platform.version").isNotEmpty()
        }
    }

    /** 细分 ROM 标识。 */
    val rom: String by lazy {
        when {
            prop("ro.mi.os.version.name").isNotEmpty() -> "hyperos"
            prop("ro.miui.ui.version.name").isNotEmpty() -> "miui"
            maker("honor") || prop("ro.build.version.magic").isNotEmpty() -> "magicos"
            maker("huawei") && isHarmony -> "harmony"
            maker("huawei") || prop("ro.build.version.emui").isNotEmpty() -> "emui"
            maker("oneplus") -> "oxygenos"
            maker("realme") -> "realmeui"
            maker("oppo") || prop("ro.build.version.opporom").isNotEmpty() || prop("ro.build.version.oplusrom").isNotEmpty() -> "coloros"
            maker("vivo", "iqoo") -> if (prop("ro.vivo.os.build.display.id").contains("OriginOS", true) ||
                prop("ro.vivo.os.name").contains("Origin", true)) "originos" else "funtouch"
            maker("samsung") -> "oneui"
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
            else -> Family.STOCK
        }
    }

    val label: String
        get() = when (rom) {
            "hyperos" -> "小米 HyperOS"
            "miui" -> "小米 MIUI"
            "harmony" -> "华为 HarmonyOS"
            "emui" -> "华为 EMUI"
            "magicos" -> "荣耀 MagicOS"
            "coloros" -> "OPPO ColorOS"
            "oxygenos" -> "一加 OxygenOS / ColorOS"
            "realmeui" -> "realme UI"
            "originos" -> "vivo OriginOS"
            "funtouch" -> "vivo Funtouch OS"
            "oneui" -> "三星 One UI"
            else -> "原生 Android"
        }

    /** 国产 ROM 默认会拦截第三方桌面快捷方式，需要用户在权限页开启。 */
    val shortcutNeedsPermission get() = family in setOf(Family.XIAOMI, Family.HUAWEI, Family.HONOR, Family.OPPO, Family.VIVO)

    /** 旧式 INSTALL_SHORTCUT 广播仍被这些启动器接受。 */
    val supportsLegacyShortcut get() = family in setOf(Family.XIAOMI, Family.HUAWEI, Family.HONOR, Family.VIVO, Family.OPPO)

    /** 系统自带“应用双开 / 分身”所用的 userId（不是工作资料，必须排除）。 */
    val oemCloneUserIds = setOf(95 /* 三星双开 */, 128 /* 华为应用分身 */, 666 /* vivo */, 999 /* 小米 / OPPO */)

    /**
     * 桌面快捷方式权限：true 允许 / false 拒绝 / null 无法判断。
     * 目前只有 MIUI / HyperOS 能可靠读取（AppOps 10017）；其他 ROM 由请求后的回执判断。
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

    // ---------------- 跳转 ----------------
    private fun cn(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun details(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())

    /** 依次尝试；返回 'rom'（厂商页）/ 'details'（应用详情）/ 'error'。 */
    fun launchFirst(act: Activity, intents: List<Intent>): String {
        for (i in intents) {
            if (tryStart(act, i)) return "rom"
        }
        if (tryStart(act, details(act))) return "details"
        return if (tryStart(act, Intent(Settings.ACTION_SETTINGS))) "details" else "error"
    }

    private fun tryStart(act: Activity, i: Intent): Boolean {
        return try {
            if (i.resolveActivity(act.packageManager) == null) return false
            act.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shortcutPermissionIntents(ctx: Context): List<Intent> {
        val p = ctx.packageName
        return when (family) {
            Family.XIAOMI -> listOf(
                // HyperOS / MIUI：桌面快捷方式是权限编辑器里的一项（AppOps 10017），不要只打开应用详情。
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
        Family.STOCK -> emptyList()
    }

    fun hasAutoStartPage(ctx: Context) = autoStartIntents(ctx).any { it.resolveActivity(ctx.packageManager) != null }

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
        return if (launchFirst(act, listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) != "error") "requested" else "error"
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
            Family.STOCK -> ""
        }
}
