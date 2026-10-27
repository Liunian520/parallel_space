package com.parallelspace.parallel_space

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * 工作资料（真实分身）相关的公共逻辑。个人空间实例、桌面快捷方式跳板、小组件共用。
 *
 * 关键点：MIUI / HyperOS 的“应用双开”（XSpace，userId 999；其他厂商的双开空间类似）同样会出现在 LauncherApps.getProfiles 里，
 * 1.1.x 把它当成了工作资料，导致“打开分身”实际启动的是系统双开的应用（并弹出系统的分身选择）。
 * 这里只接受“由平行空间创建的托管资料”。
 */
object WorkProfile {

    fun launcher(ctx: Context) = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    fun um(ctx: Context) = ctx.getSystemService(Context.USER_SERVICE) as UserManager
    fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** UserHandle.hashCode() 即 userId（公开 API 中没有 getIdentifier）。 */
    fun userId(u: UserHandle) = u.hashCode()

    private fun otherProfiles(ctx: Context): List<UserHandle> {
        val all = if (Build.VERSION.SDK_INT >= 26) launcher(ctx).profiles else um(ctx).userProfiles
        return all.filter { it != Process.myUserHandle() }
    }

    fun hasOemCloneSpace(ctx: Context) = otherProfiles(ctx).any { userId(it) in OemAdapter.oemCloneUserIds || isManagedType(ctx, it) == false }

    /** Android 15+：系统给出的用户类型；null = 未知。 */
    private fun isManagedType(ctx: Context, u: UserHandle): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return null
        return try {
            launcher(ctx).getLauncherUserInfo(u)?.userType?.let { it == UserManager.USER_TYPE_PROFILE_MANAGED }
        } catch (_: Throwable) {
            null
        }
    }

    /** 个人空间里能否解析到工作资料中本 App 的 WorkOpActivity（跨资料转发只有我们的资料所有者会添加）。 */
    fun ourForwarderExists(ctx: Context): Boolean = try {
        ctx.packageManager.queryIntentActivities(
            Intent(WorkOpActivity.ACTION).addCategory(Intent.CATEGORY_DEFAULT), 0,
        ).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** 托管资料中是否装有本 App（可启动）。 */
    private fun ourAppIn(ctx: Context, u: UserHandle): Boolean = try {
        launcher(ctx).getActivityList(ctx.packageName, u).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** 托管资料候选：排除厂商双开空间（userId / Android 15 用户类型）。 */
    private fun managedProfiles(ctx: Context): List<UserHandle> = otherProfiles(ctx).filter { u ->
        userId(u) !in OemAdapter.oemCloneUserIds && isManagedType(ctx, u) != false
    }

    // ---- 记录：创建成功后保存新资料的序列号（重启 / 升级后仍能认出自己的资料） ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("work_profile", Context.MODE_PRIVATE)

    fun rememberOwned(ctx: Context) {
        val u = managedProfiles(ctx).firstOrNull { ourAppIn(ctx, it) } ?: return
        prefs(ctx).edit().putLong("serial", um(ctx).getSerialNumberForUser(u)).apply()
    }

    private fun rememberedSerial(ctx: Context): Long = prefs(ctx).getLong("serial", -1L)

    /**
     * 判断托管资料是否归平行空间所有。个人空间进程无法直接读取其他用户的资料所有者
     * （isProfileOwnerApp 只对当前用户有效），因此按可靠程度依次判断：
     *  1. 创建成功时记录的用户序列号；
     *  2. 资料所有者添加的跨资料入口（WORK_OP 转发）能解析；
     *  3. 托管资料中装有本 App —— 托管资料只预装系统应用，第三方 App 只能由资料所有者装入，
     *     而由平行空间创建时资料所有者正是平行空间本身。
     * 1.2.0 只用了第 2 条：HyperOS 上资料所有者的初始化（PROFILE_PROVISIONING_COMPLETE 广播）
     * 没有执行 / 转发未生效时，自己的资料就被误判为“其他工作资料”。
     */
    private fun owned(ctx: Context, u: UserHandle): Boolean {
        val serial = try {
            um(ctx).getSerialNumberForUser(u)
        } catch (_: Exception) {
            -2L
        }
        if (serial == rememberedSerial(ctx)) return true
        return ourAppIn(ctx, u)
    }

    @Volatile
    private var cached: Pair<Long, UserHandle?>? = null

    /** 由平行空间创建的工作资料；不存在返回 null。缓存 2 秒（批量取图标时避免重复查询系统服务）。 */
    fun ourUser(ctx: Context): UserHandle? {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { (t, u) -> if (now - t < 2000) return u }
        val u = managedProfiles(ctx).firstOrNull { owned(ctx, it) }
        if (u != null && rememberedSerial(ctx) < 0) {
            try {
                prefs(ctx).edit().putLong("serial", um(ctx).getSerialNumberForUser(u)).apply()
            } catch (_: Exception) {
            }
        }
        cached = now to u
        return u
    }

    fun cpa(ctx: Context) = ctx.getSystemService(android.content.pm.CrossProfileApps::class.java)

    /** 工作资料是否在 CrossProfileApps 目标列表中。 */
    fun crossProfileReady(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return ourForwarderExists(ctx)
        val u = ourUser(ctx) ?: return false
        return try {
            u in cpa(ctx).targetUserProfiles
        } catch (_: Exception) {
            false
        }
    }

    /** API 30+：用户是否已授权「跨资料交互」（未授权时 startActivity 会 SecurityException）。 */
    fun canInteract(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            cpa(ctx).canInteractAcrossProfiles()
        } catch (_: Exception) {
            false
        }
    }

    fun canRequestInteract(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            cpa(ctx).canRequestInteractAcrossProfiles()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 操作通道是否可用：已授权跨资料交互且（目标资料可见 或 旧版 IntentFilter 可解析）。
     * 1.2.3 错误：status.linked 只看 forwarder，且 startOp 吞掉未授权异常 → 「初始化未能启动」。
     */
    fun linked(ctx: Context): Boolean {
        if (ourUser(ctx) == null) return false
        if (Build.VERSION.SDK_INT >= 30 && !canInteract(ctx)) return false
        return crossProfileReady(ctx) || ourForwarderExists(ctx)
    }

    /** 工作资料可能刚创建 / 删除：清除缓存。 */
    fun invalidate() {
        cached = null
    }

    /** 其他托管资料（公司 MDM / 其他分身软件创建）。 */
    fun hasForeignProfile(ctx: Context): Boolean = ourUser(ctx) == null && managedProfiles(ctx).isNotEmpty()

    /** @deprecated 保留常量避免旧调用编译失败。 */
    val REPAIR_BOUNDS = android.graphics.Rect(7, 7, 8, 8)

    fun repairLink(ctx: Context, caller: android.app.Activity? = null): Map<String, Any> {
        if (caller == null) return mapOf("r" to "no_activity", "detail" to "caller=null")
        return startOp(caller, ProfileOpActivity.OP_SETUP)
    }

    /**
     * 打开系统「跨资料应用」授权页。'ok' | 'unsupported' | 'error'
     * 用户从设置返回后需再调 status / startOp。
     */
    fun requestInteract(act: android.app.Activity): String {
        if (Build.VERSION.SDK_INT < 30) return "unsupported"
        return try {
            val c = cpa(act)
            if (c.canInteractAcrossProfiles()) return "already"
            if (!c.canRequestInteractAcrossProfiles()) return "unsupported"
            act.startActivity(c.createRequestInteractAcrossProfilesIntent())
            "ok"
        } catch (e: Exception) {
            // 部分 ROM 无标准页：尝试通用设置
            try {
                val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", act.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
                "settings"
            } catch (e2: Exception) {
                "error:${e2.javaClass.simpleName}:${e2.message}"
            }
        }
    }

    /**
     * 显式启动 ProfileOpActivity。返回 map：r = ok|need_permission|no_profile|no_target|security|error，detail = 异常摘要。
     * 严禁 startMainActivity(MainActivity)。
     */
    fun startOp(caller: android.app.Activity, op: String, pkg: String? = null, flag: Boolean = false, apk: String? = null): Map<String, Any> {
        val u = ourUser(caller) ?: return mapOf("r" to "no_profile", "detail" to "ourUser=null")
        if (Build.VERSION.SDK_INT < 30) {
            return mapOf("r" to "unsupported", "detail" to "sdk=${Build.VERSION.SDK_INT}")
        }
        val c = try {
            cpa(caller)
        } catch (e: Exception) {
            return mapOf("r" to "error", "detail" to "cpa:${e.javaClass.simpleName}:${e.message}")
        }
        if (!c.canInteractAcrossProfiles()) {
            return mapOf(
                "r" to "need_permission",
                "detail" to "canInteractAcrossProfiles=false canRequest=${try { c.canRequestInteractAcrossProfiles() } catch (_: Exception) { false }}",
                "canRequest" to canRequestInteract(caller),
            )
        }
        if (u !in c.targetUserProfiles) {
            return mapOf("r" to "no_target", "detail" to "user not in targetUserProfiles")
        }
        val i = ProfileOpActivity.intent(caller, op, pkg, flag, apk)
        if (i.component != ProfileOpActivity.component(caller)) {
            return mapOf("r" to "error", "detail" to "component rewritten to ${i.component}")
        }
        return try {
            c.startActivity(i, u, caller)
            mapOf("r" to "ok", "detail" to "started ${i.component}")
        } catch (e: SecurityException) {
            mapOf(
                "r" to "security",
                "detail" to "SecurityException:${e.message}",
                "canRequest" to canRequestInteract(caller),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            mapOf("r" to "not_found", "detail" to "ActivityNotFoundException:${e.message}")
        } catch (e: Exception) {
            mapOf("r" to "error", "detail" to "${e.javaClass.simpleName}:${e.message}")
        }
    }

    fun startOpOk(caller: android.app.Activity, op: String, pkg: String? = null, flag: Boolean = false, apk: String? = null): Boolean =
        startOp(caller, op, pkg, flag, apk)["r"] == "ok"

    /** 个人空间 APK 路径（供资料内 PackageInstaller 尝试；跨用户常读不到）。 */
    fun apkPath(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
    } catch (_: Exception) {
        null
    }

    fun status(ctx: Context): Map<String, Any> {
        invalidate()
        val pm = ctx.packageManager
        val dpm = dpm(ctx)
        val inside = dpm.isProfileOwnerApp(ctx.packageName)
        val ours = if (inside) null else ourUser(ctx)
        val state = when {
            inside -> "inside"
            ours != null && um(ctx).isQuietModeEnabled(ours) -> "paused"
            ours != null -> "ready"
            hasForeignProfile(ctx) -> "foreign"
            !pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS) -> "unsupported"
            else -> "none"
        }
        val allowed = try {
            dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        } catch (_: Exception) {
            false
        }
        return mapOf(
            "state" to state,
            "provisioningAllowed" to allowed,
            "xspace" to hasOemCloneSpace(ctx),
            "rom" to OemAdapter.rom,
            "romLabel" to OemAdapter.label,
            "romNote" to OemAdapter.workProfileNote,
            "sdk" to Build.VERSION.SDK_INT,
            "linked" to (ours != null && linked(ctx)),
            "canInteract" to canInteract(ctx),
            "canRequestInteract" to canRequestInteract(ctx),
        )
    }

    // ---------------- 应用列表 ----------------
    fun category(ai: ApplicationInfo): String {
        if (Build.VERSION.SDK_INT < 26) return if ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0) "game" else "other"
        @Suppress("DEPRECATION")
        return when (ai.category) {
            ApplicationInfo.CATEGORY_GAME -> "game"
            ApplicationInfo.CATEGORY_AUDIO -> "audio"
            ApplicationInfo.CATEGORY_VIDEO -> "video"
            ApplicationInfo.CATEGORY_IMAGE -> "image"
            ApplicationInfo.CATEGORY_SOCIAL -> "social"
            ApplicationInfo.CATEGORY_NEWS -> "news"
            ApplicationInfo.CATEGORY_MAPS -> "maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
            else -> if (Build.VERSION.SDK_INT >= 31 && ai.category == ApplicationInfo.CATEGORY_ACCESSIBILITY) "accessibility" else "other"
        }
    }

    private fun versionName(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun appMap(ctx: Context, a: LauncherActivityInfo): Map<String, Any> {
        val ai = a.applicationInfo
        return mapOf(
            "pkg" to ai.packageName,
            "label" to a.label.toString(),
            "ver" to versionName(ctx, ai.packageName),
            "cat" to category(ai),
            "system" to ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
            "suspended" to (Build.VERSION.SDK_INT >= 24 && (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0),
        )
    }

    /** 工作资料中可启动的应用（不含本 App）。state != ready 时 apps 为空，调用方不得据此删除数据。 */
    fun workApps(ctx: Context): Map<String, Any> {
        val st = status(ctx)
        val user = if (st["state"] == "ready") ourUser(ctx) else null
        val apps = user?.let { u ->
            launcher(ctx).getActivityList(null, u)
                .filter { it.applicationInfo.packageName != ctx.packageName }
                .distinctBy { it.applicationInfo.packageName }
                .map { appMap(ctx, it) }
        } ?: emptyList()
        return st + mapOf("apps" to apps)
    }

    /** 个人空间中可启动的应用（标记是否已在工作资料中）。 */
    fun personalApps(ctx: Context): List<Map<String, Any>> {
        val l = launcher(ctx)
        val inWork = ourUser(ctx)?.let { u ->
            l.getActivityList(null, u).map { it.applicationInfo.packageName }.toSet()
        } ?: emptySet()
        return l.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != ctx.packageName }
            .distinctBy { it.applicationInfo.packageName }
            .map { appMap(ctx, it) + mapOf("inWork" to inWork.contains(it.applicationInfo.packageName)) }
            .sortedBy { it["label"] as String }
    }

    // ---------------- 启动 ----------------
    /**
     * 只通过 LauncherApps 在工作资料中启动，绝不回退到个人空间（否则会启动系统双开 / 原应用）。
     * 'ok' | 'no_profile' | 'paused' | 'not_found' | 'suspended' | 'error'
     */
    fun launch(ctx: Context, pkg: String): String {
        val user = ourUser(ctx) ?: return "no_profile"
        if (um(ctx).isQuietModeEnabled(user)) {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    um(ctx).requestQuietModeEnabled(false, user)
                } catch (_: Exception) {
                }
            }
            return "paused"
        }
        val act = launcher(ctx).getActivityList(pkg, user).firstOrNull() ?: return "not_found"
        if (Build.VERSION.SDK_INT >= 24 && (act.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0) return "suspended"
        return try {
            launcher(ctx).startMainActivity(act.componentName, user, null, null)
            "ok"
        } catch (_: Exception) {
            "error"
        }
    }

    fun isInWork(ctx: Context, pkg: String): Boolean = ourUser(ctx)?.let {
        try {
            launcher(ctx).getActivityList(pkg, it).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    } ?: false

    fun isSuspendedInWork(ctx: Context, pkg: String): Boolean? {
        val u = ourUser(ctx) ?: return null
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            (launcher(ctx).getApplicationInfo(pkg, 0, u).flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- 图标 ----------------
    fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c) // AdaptiveIconDrawable 会按系统遮罩形状绘制
        return bmp
    }

    /**
     * 应用真实图标，PNG 缓存文件。work=true 时取工作资料中的版本（带公文包角标）。
     * 按 包名 + 更新时间 + 尺寸 缓存到磁盘，旧版本缓存自动清理。
     */
    fun iconFile(ctx: Context, pkg: String, work: Boolean, size: Int): File? {
        val dir = File(ctx.cacheDir, "icons").apply { mkdirs() }
        val stamp = try {
            ctx.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
        val prefix = "${if (work) "w" else "p"}_${pkg}_"
        val f = File(dir, "$prefix${stamp}_$size.png")
        if (f.exists() && f.length() > 0) return f
        val density = ctx.resources.displayMetrics.densityDpi
        val d: Drawable = try {
            if (work) {
                val u = ourUser(ctx) ?: return null
                launcher(ctx).getActivityList(pkg, u).firstOrNull()?.getBadgedIcon(density) ?: return null
            } else {
                launcher(ctx).getActivityList(pkg, Process.myUserHandle()).firstOrNull()?.getIcon(density)
                    ?: ctx.packageManager.getApplicationIcon(pkg)
            }
        } catch (_: Exception) {
            return null
        }
        val bmp = drawableToBitmap(d, size)
        return try {
            dir.listFiles { x -> x.name.startsWith(prefix) && x.name.endsWith("_$size.png") }?.forEach { it.delete() }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        } catch (_: Exception) {
            null
        }
    }

    fun iconPng(ctx: Context, pkg: String, work: Boolean, size: Int): ByteArray? =
        try {
            iconFile(ctx, pkg, work, size)?.readBytes()
        } catch (_: Exception) {
            null
        }

    fun iconBitmap(ctx: Context, pkg: String, work: Boolean, size: Int): Bitmap? =
        iconFile(ctx, pkg, work, size)?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
}
