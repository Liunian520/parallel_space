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
import android.view.accessibility.AccessibilityManager
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
            "pixel" -> "Google Pixel"
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

    /**
     * 不先卡 resolveActivity：Android 11+ 的包可见性会让厂商页解析失败，但 startActivity 仍然能打开。
     * 打不开就 catch，交给下一个地址。
     */
    private fun tryStart(act: Activity, i: Intent): Boolean {
        return try {
            act.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) {
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

    fun canDrawOverlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

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
        "sdk" to Build.VERSION.SDK_INT,
        "release" to Build.VERSION.RELEASE.orEmpty(),
        "abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
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

    /** 打开对应设置页。每条路径都在 [launchFirst] 里逐个 try。 */
    fun openCheck(act: Activity, id: String): String = when (id) {
        "overlay" -> launchFirst(act, overlayIntents(act))
        "accessibility" -> {
            ToolboxController.armAccessibility(act)
            launchFirst(act, accessibilityIntents(act))
        }
        "notification" -> launchFirst(act, notificationIntents(act))
        "battery" -> requestIgnoreBattery(act)
        "autostart" -> launchFirst(act, autoStartIntents(act))
        "shortcut" -> launchFirst(act, shortcutPermissionIntents(act).ifEmpty { listOf(details(act)) })
        "lock" -> launchFirst(act, listOf(Intent(Settings.ACTION_SECURITY_SETTINGS)))
        else -> "none"
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
            killed -> "系统可能把无障碍服务关掉了。一键填入会改成复制到剪贴板，并在 30 秒后清除。可以重新开启。"
            else -> "未开启时，填入会复制到剪贴板，30 秒后自动清除。厂商的省电策略有时会关掉无障碍服务。"
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
        if (notes.isEmpty()) notes += "当前系统版本没有需要单独处理的行为变更"
        val status = if (sdk >= 36) "ok" else "ok"
        return Check("platform", "系统版本行为", status, "Android ${Build.VERSION.RELEASE}（API $sdk）。" + notes.joinToString("；") + "。", false)
    }

    fun overlayIntents(ctx: Context): List<Intent> {
        val p = ctx.packageName
        val standard = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$p".toUri())
        val oem = when (family) {
            Family.XIAOMI -> listOf(
                cn("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", p),
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", p),
            )
            Family.HUAWEI -> listOf(
                cn("com.huawei.systemmanager", "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity"),
                cn("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity"),
            )
            Family.HONOR -> listOf(
                cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.addviewmonitor.AddViewMonitorActivity"),
                cn("com.hihonor.systemmanager", "com.hihonor.permissionmanager.ui.MainActivity"),
            )
            Family.OPPO -> listOf(
                cn("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"),
                cn("com.oplus.safecenter", "com.oplus.safecenter.sysfloatwindow.FloatWindowListActivity"),
                cn("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity"),
            )
            Family.VIVO -> listOf(
                cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity").putExtra("packagename", p),
                cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.FloatWindowManager"),
            )
            Family.SAMSUNG -> listOf(
                cn("com.samsung.android.sm", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"),
            )
            Family.STOCK -> emptyList()
        }
        return oem + standard
    }

    fun accessibilityIntents(ctx: Context): List<Intent> {
        val component = ComponentName(ctx, ToolboxAccessibilityService::class.java)
        val detail = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString()),
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
