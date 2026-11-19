package com.parallelspace.parallel_space

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 能力状态页的只读快照。
 * 不写系统设置，不申请权限，不打开相机，也不使用悬浮窗保活的「或」判断。
 */
object PermissionStatus {
    const val TOOLBOX_CHANNEL = "toolbox_overlay"
    const val MOCK_CHANNEL = "mock_location"

    fun report(ctx: Context): Map<String, Any?> {
        val decided = PermissionStatusDecision.rows(signals(ctx)).orEmpty()
        val items = ArrayList<Map<String, Any?>>(decided.size)
        for (row in decided) {
            items.add(
                mapOf(
                    "id" to row.id,
                    "group" to row.group,
                    "title" to row.title,
                    "status" to row.status,
                    "badge" to row.badge,
                    "detail" to row.detail,
                    "canOpen" to row.canOpen,
                    "openId" to row.openId,
                ),
            )
        }
        return mapOf(
            "rom" to OemAdapter.rom,
            "romLabel" to OemAdapter.label,
            "romVersion" to OemAdapter.romVersion,
            "brand" to OemAdapter.brandLabel,
            "family" to OemAdapter.family.name,
            "manufacturer" to Build.MANUFACTURER.orEmpty(),
            "model" to Build.MODEL.orEmpty(),
            "sdk" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE.orEmpty(),
            "abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "harmonyNext" to OemAdapter.isHarmonyNext,
            "harmony" to OemAdapter.isHarmony,
            "androidAppsOk" to !OemAdapter.isHarmonyNext,
            "harmonyNote" to harmonyNote(),
            "brandTip" to OemAdapter.brandTip(),
            "items" to items,
        )
    }

    private fun harmonyNote(): String = when {
        OemAdapter.isHarmonyNext -> "检测到疑似纯血鸿蒙 NEXT：不能安装或运行 Android APK，Alter Ego 无法在此系统使用。请使用仍兼容 Android 应用的 HarmonyOS 2–4 / EMUI / MagicOS。"
        OemAdapter.isHarmony || OemAdapter.rom == "emui" || OemAdapter.rom == "magicos" ->
            "当前为可运行 Android 应用的华为 / 荣耀系统。请在机型适配里查看悬浮窗、无障碍、启动管理和电池白名单。这里的颜色只表示刚才读到的结果。"
        else -> ""
    }

    private fun signals(ctx: Context): PermissionStatusDecision.Inputs {
        val inputs = PermissionStatusDecision.Inputs()
        inputs.sdk = Build.VERSION.SDK_INT
        inputs.family = OemAdapter.family.name
        inputs.rom = OemAdapter.rom
        inputs.overlaySettings = readOverlaySettings(ctx)
        inputs.overlayAppOps = readOverlayAppOps(ctx)
        inputs.a11ySecure = readSecureListed(ctx)
        inputs.a11yManager = readManagerListed(ctx)
        inputs.a11yConnected = readConnected()
        inputs.a11yRestricted = try {
            A11yJump.restricted(ctx)
        } catch (_: Throwable) {
            null
        }
        inputs.postNotifications = readPostNotifications(ctx)
        inputs.notificationsEnabled = readNotificationsEnabled(ctx)
        inputs.toolboxChannel = readChannel(ctx, TOOLBOX_CHANNEL)
        inputs.mockChannel = readChannel(ctx, MOCK_CHANNEL)
        inputs.ignoringBattery = readIgnoringBattery(ctx)
        inputs.backgroundRestricted = readBackgroundRestricted(ctx)
        inputs.hostCamera = readPermission(ctx, android.Manifest.permission.CAMERA)
        inputs.hostMic = readPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
        inputs.xiaomiShortcut = readXiaomiShortcut(ctx)
        return inputs
    }

    private fun readOverlaySettings(ctx: Context): Boolean? = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        null
    }

    private fun readOverlayAppOps(ctx: Context): Boolean? = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                ctx.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                ctx.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        null
    }

    private fun readSecureListed(ctx: Context): Boolean? = try {
        val flat = ComponentName(ctx, ToolboxAccessibilityService::class.java).flattenToString()
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        enabled && raw.orEmpty().split(':').any { it.equals(flat, ignoreCase = true) }
    } catch (_: Throwable) {
        null
    }

    private fun readManagerListed(ctx: Context): Boolean? = try {
        val manager = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.name == ToolboxAccessibilityService::class.java.name }
    } catch (_: Throwable) {
        null
    }

    private fun readConnected(): Boolean? = try {
        ToolboxAccessibilityService.instance != null
    } catch (_: Throwable) {
        null
    }

    private fun readPostNotifications(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return null
        return readPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun readNotificationsEnabled(ctx: Context): Boolean? = try {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.areNotificationsEnabled()
    } catch (_: Throwable) {
        null
    }

    private fun readChannel(ctx: Context, id: String): String {
        if (Build.VERSION.SDK_INT < 26) return "missing"
        return try {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(id) ?: return "missing"
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) "blocked" else "open"
        } catch (_: Throwable) {
            "error"
        }
    }

    private fun readIgnoringBattery(ctx: Context): Boolean? = try {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) {
        null
    }

    private fun readBackgroundRestricted(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.isBackgroundRestricted
        } catch (_: Throwable) {
            null
        }
    }

    private fun readPermission(ctx: Context, permission: String): Boolean? = try {
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        null
    }

    private fun readXiaomiShortcut(ctx: Context): Boolean? = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val mode = method.invoke(ops, 10017, Process.myUid(), ctx.packageName) as Int
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        null
    }
}
