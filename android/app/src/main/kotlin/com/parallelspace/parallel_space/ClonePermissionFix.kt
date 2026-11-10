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
 * 分身进程里的运行时权限 / 相机修复（1.3.28–1.3.31）。
 *
 * 根因：
 * - 分身进程真实 uid 是宿主 uid，但 Bcore 把 Binder 调用里的 uid 换成虚拟 BUid、包名是分身包名。
 * - Android 14/15 的 checkSelfPermission 走 IActivityManager.checkPermissionForDevice /
 *   IPermissionManager.checkPermission，Bcore 没挂这些方法 → DENIED，授权弹窗又不弹。
 * - Bcore 完全没代理 media.camera → cameraserver 拒连 → 预览全黑。
 * - Bcore 的 AppOps NoteOp 只对麦克风类 op 直接放行，CAMERA 的 noteOp 仍带分身身份走系统 →
 *   部分机型（尤其 HyperOS）在 connect 成功后 noteOp 失败，画面仍黑。
 *
 * 1.3.30：AppOps CAMERA 曾无条件 MODE_ALLOWED。宿主拒绝时那是假授权，1.3.31 去掉。
 * 分身第一次 resume 只记录相机 id，不再 openCamera（1.3.30 的探测会占住相机，
 * 但 1.3.28/29 没有这段也黑屏，所以这不是黑屏根因）。
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
    @Volatile private var cameraStub: CameraServiceStub? = null
    @Volatile private var probed = false
    private var opLogged = 0

    /**
     * Camera1（android.hardware.Camera.open）在 native 里直接连 cameraserver，不经 Java 代理，
     * 包名来自 ActivityThread.currentOpPackageName() / Context.getOpPackageName()。
     */
    fun checkOpPackage(ctx: android.content.Context?, where: String) {
        if (ctx == null) return
        try {
            val host = BlackBoxCore.getHostPkg()
            val op = try { ctx.opPackageName } catch (_: Throwable) { null }
            val cur = try {
                Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null) as? String
            } catch (_: Throwable) { null }
            val attr = if (Build.VERSION.SDK_INT >= 31) try {
                ctx.attributionSource.let { "${it.packageName}/${it.uid}" }
            } catch (_: Throwable) { "?" } else "-"
            val bad = (op != null && op != host) || (cur != null && cur != host)
            if (bad || opLogged < 3) {
                opLogged++
                CameraDiag.log(
                    "opPkg@$where op=$op current=$cur attr=$attr host=$host/${BlackBoxCore.getHostUid()} " +
                        "bUid=${try { BlackBoxCore.getBUid() } catch (_: Throwable) { -1 }} " +
                        "sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}/${Build.MODEL}"
                )
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
                        apply()
                        checkOpPackage(a, "afterOnCreate")
                    }
                    override fun onActivityCreated(act: android.app.Activity, b: android.os.Bundle?) {
                        checkOpPackage(act, "created ${act.javaClass.simpleName}")
                    }
                    override fun onActivityResumed(act: android.app.Activity) {
                        checkOpPackage(act, "resume ${act.javaClass.simpleName}")
                        // 代理可能被其它代码清掉，resume 时再确保一次
                        ensureCameraProxy()
                        probeOnce(act)
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
                    "IAppOpsManagerProxy" -> listOf(
                        "noteOp", "noteOpNoThrow", "noteOperation", "noteProxyOperation",
                    ).forEach { wrapCameraOp(stub, it) }
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "perm hooks", t) }
        ensureCameraProxy()
    }

    private fun ensureCameraProxy() {
        try {
            if (cameraStub != null) {
                // 确认 ServiceManager 缓存仍是我们的代理
                try {
                    val sm = Class.forName("android.os.ServiceManager")
                    val b = sm.getMethod("getService", String::class.java).invoke(null, CameraServiceStub.NAME)
                    if (b !== cameraStub) {
                        CameraDiag.log("media.camera cache lost, re-inject")
                        cameraStub?.injectHook()
                        resetCameraManagerCache()
                    }
                } catch (_: Throwable) {}
                return
            }
            val s = CameraServiceStub.create() ?: return
            s.injectHook()
            cameraStub = s
            resetCameraManagerCache()
            CameraDiag.log("media.camera proxy installed")
        } catch (t: Throwable) {
            Log.w(TAG, "camera hook", t)
            CameraDiag.log("camera hook FAIL $t")
        }
    }

    /**
     * 分身进程首次 resume：只列相机 id 和包名，不 openCamera。
     * 1.3.30 在这里打开相机会和扫码抢设备；1.3.28/29 没有打开也黑屏，所以打开不能当修复。
     */
    private fun probeOnce(act: android.app.Activity) {
        if (probed) return
        probed = true
        Thread({
            try {
                val host = BlackBoxCore.getHostPkg()
                val target = try { act.applicationInfo.targetSdkVersion } catch (_: Throwable) { -1 }
                val cm = act.applicationContext.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val ids = try { cm.cameraIdList.toList() } catch (t: Throwable) {
                    CameraDiag.log("probe getCameraIdList FAIL $t"); return@Thread
                }
                CameraDiag.log(
                    "probe ids=${ids.joinToString()} op=${try { act.opPackageName } catch (_: Throwable) { "?" }} " +
                        "host=$host sdk=${Build.VERSION.SDK_INT} target=$target open=skipped"
                )
            } catch (t: Throwable) {
                CameraDiag.log("probe FAIL $t")
            }
        }, "alterego-cam-probe").start()
    }

    /** 宿主上下文上的真实授权。读不到就返回 null，调用方不得据此放行。 */
    private fun hostCameraGranted(): Boolean? = try {
        val ctx = BlackBoxCore.getContext() ?: return null
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { null }

    private var cameraOpLogs = 0

    private fun logCameraOp(msg: String) {
        synchronized(this) {
            if (cameraOpLogs >= 8) return
            cameraOpLogs++
        }
        CameraDiag.log(msg)
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

    @Suppress("UNCHECKED_CAST")
    private fun wrapCameraOp(stub: ClassInvocationStub, name: String) {
        val mapF = ClassInvocationStub::class.java.getDeclaredField("mMethodHookMap").apply { isAccessible = true }
        val map = mapF.get(stub) as? MutableMap<String, MethodHook> ?: return
        val old = map[name]
        if (old is CameraOpHook) return
        stub.addMethodHook(name, CameraOpHook(old))
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

    /**
     * AppOps 的 noteOp*：只有宿主确实已授权时才把 CAMERA 短路成 MODE_ALLOWED。
     * 系统拒绝或读不到结果时不改返回值。
     */
    private class CameraOpHook(private val old: MethodHook?) : MethodHook() {
        override fun hook(who: Any?, method: Method, args: Array<Any?>?): Any? {
            val opName = opPublicName(args)
            val cameraOp = opName != null && opName.uppercase().contains("CAMERA")
            if (cameraOp) {
                val granted = hostCameraGranted()
                val forced = CameraOpDecision.forceModeAllowed(true, granted)
                if (forced != null) {
                    logCameraOp("AppOps ${method.name} $opName → ALLOWED hostGranted=true")
                    return forced
                }
                // 不交给 Bcore 的 NoteOperation / NoteProxyOperation：它们对所有 op 直接返回 0。
                // 拒绝或未知时改写身份后走系统，由系统决定。
                logCameraOp("AppOps ${method.name} $opName hostGranted=$granted — system")
                if (args != null) {
                    try { rewriteAppOpsArgs(args) } catch (_: Throwable) {}
                }
                return try {
                    method.invoke(who, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) { throw e.cause ?: e }
            }
            // 非相机：把分身包名 / 虚拟 uid 换成宿主，再交给原来的 hook。
            if (args != null) {
                try { rewriteAppOpsArgs(args) } catch (_: Throwable) {}
            }
            return if (old != null) old.hook(who, method, args) else try {
                method.invoke(who, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) { throw e.cause ?: e }
        }

        private fun opPublicName(args: Array<Any?>?): String? {
            if (args.isNullOrEmpty()) return null
            return try {
                when (val a0 = args[0]) {
                    is Int -> {
                        val m = Class.forName("android.app.AppOpsManager")
                            .getMethod("opToPublicName", Int::class.javaPrimitiveType)
                        m.invoke(null, a0) as? String
                    }
                    is String -> a0
                    else -> null
                }
            } catch (_: Throwable) { null }
        }

        private fun rewriteAppOpsArgs(a: Array<Any?>) {
            val hostPkg = BlackBoxCore.getHostPkg()
            val hostUid = BlackBoxCore.getHostUid()
            val appPkg = try { BlackBoxCore.getAppPackageName() } catch (_: Throwable) { null }
            val bUid = try { BlackBoxCore.getBUid() } catch (_: Throwable) { -2 }
            for (i in a.indices) {
                when (val v = a[i]) {
                    is String -> if (v == appPkg || (v != hostPkg && looksLikePackage(v))) a[i] = hostPkg
                    is Int -> if (bUid > 0 && v == bUid) a[i] = hostUid
                    else -> if (v != null) {
                        val n = v.javaClass.name
                        if (n.endsWith("AttributionSource") || n.endsWith("AttributionSourceState")) {
                            fixAttributionObject(v, hostPkg, hostUid)
                        }
                    }
                }
            }
        }
    }

    /** cameraserver 代理：所有调用里的包名 / uid / AttributionSource* 改为宿主。 */
    class CameraServiceStub private constructor(binder: IBinder) : BinderInvocationStub(binder) {
        private val raw = binder
        companion object {
            const val NAME = "media.camera"
            fun create(): CameraServiceStub? {
                val sm = Class.forName("android.os.ServiceManager")
                val b = sm.getMethod("getService", String::class.java).invoke(null, NAME) as? IBinder ?: return null
                if (b is CameraServiceStub) return b
                if (b is BinderInvocationStub) {
                    CameraDiag.log("media.camera already proxied by ${b.javaClass.simpleName}")
                    // 仍包一层我们的，确保 fixArgs 生效
                }
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
                name == "getCameraCharacteristics" || name == "addListener" || name == "addListenerForDevice" ||
                name.startsWith("is") || name.startsWith("supports")
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
                when (val v = a[i]) {
                    is String -> {
                        if (v == appPkg) a[i] = hostPkg
                        else if (v != hostPkg && looksLikePackage(v)) a[i] = hostPkg
                    }
                    is Int -> if (bUid > 0 && v == bUid) a[i] = hostUid
                    else -> if (v != null) {
                        val n = v.javaClass.name
                        if (n.endsWith("AttributionSource") || n.endsWith("AttributionSourceState")) {
                            fixAttributionObject(v, hostPkg, hostUid)
                        }
                    }
                }
            }
        }
    }

    private fun looksLikePackage(s: String): Boolean =
        s.length in 3..256 && s.contains('.') && s.any { it.isLetter() } &&
            !s.startsWith("/") && !s.startsWith("android.permission.") &&
            !s.startsWith("android.hardware.") && s != "android"

    private fun fixAttributionObject(obj: Any, hostPkg: String, hostUid: Int) {
        val n = obj.javaClass.name
        if (n.endsWith("AttributionSourceState")) {
            fixAttributionState(obj, hostPkg, hostUid)
            return
        }
        // AttributionSource：改内部 State，并递归 next
        try {
            val f = obj.javaClass.getDeclaredField("mAttributionSourceState").apply { isAccessible = true }
            val state = f.get(obj)
            if (state != null) fixAttributionState(state, hostPkg, hostUid)
        } catch (_: Throwable) {
            try {
                // 部分 OEM 字段名不同
                for (fn in listOf("mAttributionSourceState", "attributionSourceState", "mState")) {
                    try {
                        val f = obj.javaClass.getDeclaredField(fn).apply { isAccessible = true }
                        val state = f.get(obj) ?: continue
                        fixAttributionState(state, hostPkg, hostUid)
                        break
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        try {
            val next = obj.javaClass.methods.firstOrNull { it.name == "getNext" && it.parameterTypes.isEmpty() }
                ?.invoke(obj)
            if (next != null) fixAttributionObject(next, hostPkg, hostUid)
        } catch (_: Throwable) {}
        // 再用 BlackBox 工具兜底
        try { top.niunaijun.blackbox.utils.compat.ContextCompat.fixAttributionSourceState(obj, hostUid) } catch (_: Throwable) {}
        try { top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceUid(obj) } catch (_: Throwable) {}
    }

    private fun fixAttributionState(s: Any, hostPkg: String, hostUid: Int) {
        val c = s.javaClass
        fun setStr(field: String, value: String) {
            try { c.getField(field).set(s, value) } catch (_: Throwable) {
                try { c.getDeclaredField(field).apply { isAccessible = true }.set(s, value) } catch (_: Throwable) {}
            }
        }
        fun setInt(field: String, value: Int) {
            try { c.getField(field).setInt(s, value) } catch (_: Throwable) {
                try { c.getDeclaredField(field).apply { isAccessible = true }.setInt(s, value) } catch (_: Throwable) {}
            }
        }
        setStr("packageName", hostPkg)
        setInt("uid", hostUid)
        // pid：仅在无效时补成真实 pid（1.3.29 起不再强行改写有效 pid）
        try {
            val pidF = try { c.getField("pid") } catch (_: Throwable) { c.getDeclaredField("pid").apply { isAccessible = true } }
            val cur = pidF.getInt(s)
            if (cur <= 0) pidF.setInt(s, Process.myPid())
        } catch (_: Throwable) {}
    }

    /** 若 CameraManagerGlobal 已缓存原始 ICameraService，清掉让它重新取（拿到代理）。 */
    private fun resetCameraManagerCache() {
        try {
            val g = Class.forName("android.hardware.camera2.CameraManager\$CameraManagerGlobal")
            val inst = g.getDeclaredField("gCameraManager").apply { isAccessible = true }.get(null) ?: return
            val f = g.getDeclaredField("mCameraService").apply { isAccessible = true }
            val cur = f.get(inst) ?: return
            if (!java.lang.reflect.Proxy.isProxyClass(cur.javaClass)) {
                f.set(inst, null)
                CameraDiag.log("CameraManagerGlobal cache cleared")
            }
        } catch (t: Throwable) { CameraDiag.log("reset cache: ${t.javaClass.simpleName}") }
        // 同步清 ServiceManager.sCache 再写入由 injectHook/replaceSystemService 完成
    }
}
