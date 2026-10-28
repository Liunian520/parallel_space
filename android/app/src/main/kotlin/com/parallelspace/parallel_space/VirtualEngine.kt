package com.parallelspace.parallel_space

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.entity.pm.InstallResult

/**
 * 进程内应用虚拟化（NewBlackbox Bcore，Apache-2.0）。
 * 用户空间实现，不要求 Root；不暴露指纹伪装 / 计算器伪装 / 隐藏最近任务 / Xposed。
 * HyperOS 上部分应用仍可能因系统限制无法克隆，失败时返回明确错误。
 */
object VirtualEngine {
    private const val TAG = "VirtualEngine"
    private const val USER = 0

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var ready = false

    @Volatile
    private var attachOk = false

    @Volatile
    private var lastError: String? = null

    val available: Boolean get() = ready
    val error: String? get() = lastError

    fun attach(base: Context) {
        appCtx = base.applicationContext ?: base
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isEnableLauncherActivity(): Boolean = false // 不用引擎自带启动器 UI
                override fun isEnableDaemonService(): Boolean = true
                // 不开启隐藏 Root / 额外伪装相关开关的默认行为以外的能力
            })
            attachOk = true
            if (lastError?.startsWith("attach:") == true) lastError = null
        } catch (t: Throwable) {
            attachOk = false
            lastError = "attach: ${describe(t)}"
            Log.e(TAG, "attach failed", t)
            ready = false
        }
    }

    fun create(ctx: Context) {
        if (!attachOk) {
            if (lastError == null) lastError = "attach: not called"
            ready = false
            return
        }
        try {
            BlackBoxCore.get().doCreate()
            ready = true
            lastError = null
            Log.i(TAG, "BlackBox ready")
        } catch (t: Throwable) {
            lastError = "create: ${describe(t)}"
            Log.e(TAG, "create failed", t)
            ready = false
        }
    }

    /**
     * 冷启动 attach 失败后，从界面再试一次。
     * attach 已成功则只重跑 create，避免把已分类的进程再挂一次钩子。
     */
    fun retry(ctx: Context): Map<String, Any?> {
        if (!ready) {
            val base = ctx.applicationContext ?: ctx
            if (!attachOk) attach(base)
            if (attachOk) create(base)
        }
        return status()
    }

    /** 带上 cause，避免 ART 把失败的 <clinit> 收成一句 NoClassDefFoundError: BlackBoxCore。 */
    private fun describe(t: Throwable): String {
        val seen = HashSet<Throwable>()
        val parts = ArrayList<String>(4)
        var cur: Throwable? = t
        while (cur != null && seen.add(cur) && parts.size < 4) {
            val msg = cur.message?.trim().orEmpty()
            parts += if (msg.isEmpty()) cur.javaClass.simpleName else "${cur.javaClass.simpleName}: $msg"
            cur = cur.cause
        }
        return parts.joinToString(" ← ")
    }

    fun status(): Map<String, Any?> = mapOf(
        "available" to ready,
        "error" to lastError,
        "engine" to "NewBlackbox/Bcore",
        "license" to "Apache-2.0",
        "userId" to USER,
    )

    /** 已有的 BlackBox 用户 id（含 0）。每个 userId 是一份独立数据空间。 */
    fun userIds(): List<Int> = try {
        (listOf(USER) + BlackBoxCore.get().getUsers().mapNotNull { u -> userIdOf(u) }).distinct().sorted()
    } catch (_: Throwable) {
        listOf(USER)
    }

    private fun userIdOf(u: Any?): Int? = try {
        u?.javaClass?.getField("id")?.getInt(u)
    } catch (_: Throwable) {
        null
    }

    private fun ensureUser(userId: Int) {
        if (userId == USER) return
        if (userIds().contains(userId)) return
        try {
            BlackBoxCore.get().createUser(userId)
        } catch (t: Throwable) {
            Log.w(TAG, "createUser $userId", t)
        }
    }

    /** 每个 (包名, userId) 是一个分身实例。 */
    fun listCloned(): List<Map<String, Any>> {
        if (!ready) return emptyList()
        val out = ArrayList<Map<String, Any>>()
        for (uid in userIds()) {
            try {
                for (pi in BlackBoxCore.get().getInstalledPackages(0, uid)) {
                    val pkg = pi.packageName ?: continue
                    if (pkg == BlackBoxCore.getHostPkg()) continue
                    val label = try {
                        pi.applicationInfo?.loadLabel(BlackBoxCore.getPackageManager())?.toString() ?: pkg
                    } catch (_: Exception) {
                        pkg
                    }
                    val system = (pi.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                    out += mapOf(
                        "pkg" to pkg,
                        "label" to label,
                        "ver" to (pi.versionName ?: ""),
                        "cat" to "other",
                        "system" to system,
                        "suspended" to false,
                        "inWork" to true,
                        "userId" to uid,
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "listCloned user=$uid", t)
            }
        }
        return out
    }

    fun isCloned(pkg: String, userId: Int = USER): Boolean = try {
        ready && BlackBoxCore.get().isInstalled(pkg, userId)
    } catch (_: Throwable) {
        false
    }

    /** 下一个没装这个包的 userId（用于「再分身一个」）。 */
    fun nextFreeUser(pkg: String): Int {
        var id = USER
        while (id < 64) {
            if (!isCloned(pkg, id)) return id
            id++
        }
        return id
    }

    /**
     * @param userId <0 表示新开一个实例（取下一个空闲 userId）。
     */
    fun clone(pkg: String, userId: Int = USER): Map<String, Any?> {
        if (!ready) return mapOf("r" to "engine_unavailable", "detail" to (lastError ?: "not ready"))
        return try {
            val target = if (userId < 0) nextFreeUser(pkg) else userId
            if (BlackBoxCore.get().isInstalled(pkg, target)) {
                return mapOf("r" to "ok", "detail" to "already", "userId" to target)
            }
            ensureUser(target)
            val result: InstallResult = BlackBoxCore.get().installPackageAsUser(pkg, target)
            if (result.success) mapOf("r" to "ok", "detail" to (result.packageName ?: pkg), "userId" to target)
            else mapOf(
                "r" to "rejected",
                "detail" to (result.msg ?: "install failed — 该应用可能无法在当前系统上虚拟化"),
                "userId" to target,
            )
        } catch (t: Throwable) {
            mapOf("r" to "error", "detail" to "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun launch(pkg: String, userId: Int = USER): Map<String, Any?> {
        if (!ready) return mapOf("r" to "engine_unavailable", "detail" to (lastError ?: "not ready"))
        return try {
            if (!BlackBoxCore.get().isInstalled(pkg, userId)) {
                return mapOf("r" to "not_found", "detail" to "not cloned")
            }
            val ok = BlackBoxCore.get().launchApk(pkg, userId)
            if (ok) {
                try {
                    appCtx?.let { ToolboxController.onCloneLaunched(it, pkg, userId) }
                } catch (_: Throwable) {
                }
                mapOf("r" to "ok")
            } else {
                mapOf("r" to "error", "detail" to "launchApk returned false")
            }
        } catch (t: Throwable) {
            mapOf("r" to "error", "detail" to "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 只删除这个实例（该 userId 下的包和数据），不碰其他分身。 */
    fun delete(pkg: String, userId: Int = USER): Map<String, Any?> {
        if (!ready) return mapOf("r" to "engine_unavailable", "detail" to (lastError ?: "not ready"))
        return try {
            BlackBoxCore.get().uninstallPackageAsUser(pkg, userId)
            mapOf("r" to "ok")
        } catch (t: Throwable) {
            mapOf("r" to "error", "detail" to "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
