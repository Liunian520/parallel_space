package com.parallelspace.parallel_space

import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub
import top.niunaijun.blackbox.fake.hook.HookManager
import top.niunaijun.blackbox.fake.hook.IInjectHook
import top.niunaijun.blackbox.fake.hook.MethodHook
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 分身进程里的运行时权限 / 相机修复（1.3.28）。
 *
 * 根因：分身进程真实 uid 是宿主 uid，但 Bcore 把 Binder 调用里的 uid 换成虚拟 BUid、包名是分身包名。
 * - Android 14/15 的 checkSelfPermission 走 IActivityManager.checkPermissionForDevice /
 *   IPermissionManager.checkPermission(分身包名, …)，Bcore 没挂这些方法 → 系统拿虚拟 uid / 未授权的
 *   真机同名包去查 → DENIED，于是分身弹「需要相机权限」，而系统授权弹窗认的是宿主（已授权）→ 不弹。
 * - Bcore 完全没代理 media.camera，ICameraService.connectDevice 带着分身包名 / 虚拟 uid →
 *   cameraserver 校验包名与调用 uid 不符直接拒绝 → 预览全黑。
 * 修复：危险权限一律按「宿主包名 + 宿主 uid + 宿主 userId」去真查；相机服务的所有调用把包名、uid、
 * AttributionSourceState 改回宿主。宿主没授权时由 MainActivity 在启动分身前弹系统真弹窗。
 */
object ClonePermissionFix {
    private const val TAG = "ClonePermFix"

    val DANGEROUS: Set<String> = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.POST_NOTIFICATIONS",
    )

    /** 宿主启动分身前需要向系统申请的（不含后台定位，需单独授权）。 */
    val REQUESTABLE: Set<String> = DANGEROUS - "android.permission.ACCESS_BACKGROUND_LOCATION"

    @Volatile private var lifecycleAdded = false
    private var opLogged = 0

    /**
     * Camera1（android.hardware.Camera.open）在 native 里直接连 cameraserver，不经 Java 代理，
     * 包名来自 ActivityThread.currentOpPackageName() / Context.getOpPackageName()。
     * 这里确认它们是宿主包名；不是就用 Bcore 的 ContextCompat.fix 再修一次并写诊断日志。
     */
    fun checkOpPackage(ctx: android.content.Context?, where: String) {
        if (ctx == null) return
        try {
            val host = BlackBoxCore.getHostPkg()
            val op = try { ctx.opPackageName } catch (_: Throwable) { null }
            val cur = try {
                Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null) as? String
            } catch (_: Throwable) { null }
            val attr = if (Build.VERSION.SDK_INT >= 31) try { ctx.attributionSource.let { "${it.packageName}/${it.uid}" } } catch (_: Throwable) { "?" } else "-"
            val bad = (op != null && op != host) || (cur != null && cur != host)
            if (bad || opLogged < 3) {
                opLogged++
                CameraDiag.log("opPkg@$where op=$op current=$cur attr=$attr host=$host/${BlackBoxCore.getHostUid()} bUid=${try { BlackBoxCore.getBUid() } catch (_: Throwable) { -1 }} sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}")
            }
            if (bad) {
                var c: android.content.Context? = ctx
                while (c is android.content.ContextWrapper) {
                    try { top.niunaijun.blackbox.utils.compat.ContextCompat.fix(c) } catch (_: Throwable) {}
                    c = c.baseContext
                }
                try { top.niunaijun.blackbox.utils.compat.ContextCompat.fix(ctx.applicationContext) } catch (_: Throwable) {}
                CameraDiag.log("opPkg fixed → ${try { ctx.opPackageName } catch (_: Throwable) { "?" }}")
            }
        } catch (t: Throwable) { CameraDiag.log("checkOpPackage $where: $t") }
    }
    @Volatile private var cameraStub: CameraServiceStub? = null

    fun install() {
        try {
            if (!BlackBoxCore.get().isBlackProcess) return
        } catch (_: Throwable) { return }
        apply()
        if (!lifecycleAdded) {
            lifecycleAdded = true
            try {
                BlackBoxCore.get().addAppLifecycleCallback(object :
                    top.niunaijun.blackbox.app.configuration.AppLifecycleCallback() {
                    override fun beforeApplicationOnCreate(p: String?, pn: String?, a: android.app.Application?, u: Int) {
                        apply()
                        checkOpPackage(a, "beforeOnCreate")
                    }
                    override fun afterApplicationOnCreate(p: String?, pn: String?, a: android.app.Application?, u: Int) {
                        checkOpPackage(a, "afterOnCreate")
                    }
                    override fun onActivityResumed(act: android.app.Activity) {
                        checkOpPackage(act, "resume ${act.javaClass.simpleName}")
                    }
                })
            } catch (t: Throwable) { Log.w(TAG, "lifecycle", t) }
        }
    }

    private fun apply() {
        try {
            val injectors = injectors() ?: return
            for ((cls, hook) in injectors) {
                val stub = hook as? ClassInvocationStub ?: continue
                when (cls.simpleName) {
                    "IActivityManagerProxy" -> listOf(
                        "checkPermission", "checkPermissionForDevice", "checkPermissionWithToken",
                    ).forEach { wrap(stub, it, Kind.UID) }
                    "IPackageManagerProxy" -> {
                        wrap(stub, "checkPermission", Kind.PKG_USER)
                        wrap(stub, "checkUidPermission", Kind.UID)
                    }
                    "IPermissionManagerProxy" -> {
                        wrap(stub, "checkPermission", Kind.PKG_USER)
                        wrap(stub, "checkUidPermission", Kind.UID)
                    }
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "perm hooks", t) }
        try {
            if (cameraStub == null) {
                val s = CameraServiceStub.create() ?: return
                s.injectHook()
                cameraStub = s
                resetCameraManagerCache()
                CameraDiag.log("media.camera proxy installed")
            }
        } catch (t: Throwable) { Log.w(TAG, "camera hook", t); CameraDiag.log("camera hook FAIL $t") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectors(): Map<Class<*>, IInjectHook>? {
        val f = HookManager::class.java.getDeclaredField("mInjectors").apply { isAccessible = true }
        return (f.get(HookManager.get()) as? Map<Class<*>, IInjectHook>)?.toMap()
    }

    private enum class Kind { UID, PKG_USER }

    @Suppress("UNCHECKED_CAST")
    private fun wrap(stub: ClassInvocationStub, name: String, kind: Kind) {
        val mapF = ClassInvocationStub::class.java.getDeclaredField("mMethodHookMap").apply { isAccessible = true }
        val map = mapF.get(stub) as? MutableMap<String, MethodHook> ?: return
        val old = map[name]
        if (old is PermHook) return
        stub.addMethodHook(name, PermHook(old, kind))
    }

    private class PermHook(private val old: MethodHook?, private val kind: Kind) : MethodHook() {
        override fun hook(who: Any?, method: Method, args: Array<Any?>?): Any? {
            val perm = args?.firstOrNull { it is String && it.startsWith("android.permission.") } as? String
            if (args != null && perm != null && perm in DANGEROUS) {
                val fixed = args.copyOf()
                val hostUid = BlackBoxCore.getHostUid()
                val bUid = try { BlackBoxCore.getBUid() } catch (_: Throwable) { -2 }
                val appPkg = try { BlackBoxCore.getAppPackageName() } catch (_: Throwable) { null }
                val types = method.parameterTypes
                var lastIntIdx = -1
                for (i in fixed.indices) {
                    val v = fixed[i]
                    if (v is String && v != perm && (v == appPkg)) fixed[i] = BlackBoxCore.getHostPkg()
                    if (types.getOrNull(i) == Int::class.javaPrimitiveType) {
                        lastIntIdx = i
                        if (kind == Kind.UID && (v == bUid || v == Process.myUid())) fixed[i] = hostUid
                    }
                }
                if (kind == Kind.PKG_USER && lastIntIdx >= 0) fixed[lastIntIdx] = BlackBoxCore.getHostUserId()
                return try {
                    method.invoke(who, *fixed)
                } catch (e: InvocationTargetException) {
                    throw e.cause ?: e
                }
            }
            return if (old != null) old.hook(who, method, args) else try {
                method.invoke(who, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) { throw e.cause ?: e }
        }
    }

    /** cameraserver 代理：所有调用里的包名 / uid / AttributionSourceState 改为宿主。 */
    class CameraServiceStub private constructor(binder: IBinder) : BinderInvocationStub(binder) {
        private val raw = binder
        companion object {
            const val NAME = "media.camera"
            fun create(): CameraServiceStub? {
                val sm = Class.forName("android.os.ServiceManager")
                val b = sm.getMethod("getService", String::class.java).invoke(null, NAME) as? IBinder ?: return null
                if (b is BinderInvocationStub) { CameraDiag.log("media.camera already proxied by ${b.javaClass.simpleName}"); return null }
                return CameraServiceStub(b)
            }
        }

        override fun getWho(): Any? {
            val stub = Class.forName("android.hardware.ICameraService\$Stub")
            return stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw)
        }

        override fun inject(base: Any?, proxy: Any?) {
            replaceSystemService(NAME)
        }

        override fun isBadEnv(): Boolean = false

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val name = method.name
            val interesting = name.startsWith("connect") || name == "getNumberOfCameras" || name.startsWith("getCameraInfo") ||
                name == "getCameraCharacteristics" || name == "addListener" || name == "addListenerForDevice"
            val orig = args?.copyOf()
            if (args != null) {
                try { fixArgs(args) } catch (t: Throwable) { CameraDiag.log("fixArgs $name: $t") }
            }
            if (interesting) CameraDiag.log("-> $name(" + (args?.joinToString { CameraDiag.summarize(it) } ?: "") + ")")
            return try {
                val r = method.invoke(base, *(args ?: emptyArray()))
                if (interesting) CameraDiag.log("<- $name ok ${CameraDiag.summarize(r)}")
                r
            } catch (e: InvocationTargetException) {
                val c = e.cause ?: e
                CameraDiag.log("<- $name FAIL ${c.javaClass.simpleName}: ${c.message}")
                if (orig != null && name.startsWith("connect") && !orig.contentEquals(args)) {
                    // 改写后的参数被拒时，用原参数再试一次
                    try {
                        val r2 = method.invoke(base, *orig)
                        CameraDiag.log("<- $name retry-orig ok")
                        return r2
                    } catch (e2: InvocationTargetException) {
                        CameraDiag.log("<- $name retry-orig FAIL ${e2.cause}")
                    }
                }
                throw c
            }
        }

        private fun fixArgs(a: Array<Any?>) {
            val hostPkg = BlackBoxCore.getHostPkg()
            val hostUid = BlackBoxCore.getHostUid()
            val appPkg = try { BlackBoxCore.getAppPackageName() } catch (_: Throwable) { null }
            val bUid = try { BlackBoxCore.getBUid() } catch (_: Throwable) { -2 }
            for (i in a.indices) {
                val v = a[i]
                when {
                    v is String && appPkg != null && v == appPkg -> a[i] = hostPkg
                    v is Int && bUid > 0 && v == bUid -> a[i] = hostUid
                    v != null && v.javaClass.name == "android.content.AttributionSourceState" -> fixAttribution(v, hostPkg, hostUid)
                }
            }
        }

        private fun fixAttribution(s: Any, hostPkg: String, hostUid: Int) {
            val c = s.javaClass
            try { c.getField("packageName").set(s, hostPkg) } catch (_: Throwable) {}
            try { c.getField("uid").setInt(s, hostUid) } catch (_: Throwable) {}
        }
    }

    /** 若 CameraManagerGlobal 已缓存原始 ICameraService，清掉让它重新取（拿到代理）。 */
    private fun resetCameraManagerCache() {
        try {
            val g = Class.forName("android.hardware.camera2.CameraManager\$CameraManagerGlobal")
            val inst = g.getDeclaredField("gCameraManager").apply { isAccessible = true }.get(null) ?: return
            val f = g.getDeclaredField("mCameraService").apply { isAccessible = true }
            val cur = f.get(inst) ?: return
            if (!java.lang.reflect.Proxy.isProxyClass(cur.javaClass)) { f.set(inst, null); CameraDiag.log("CameraManagerGlobal cache cleared") }
        } catch (t: Throwable) { CameraDiag.log("reset cache: ${t.javaClass.simpleName}") }
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
