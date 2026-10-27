package com.parallelspace.parallel_space

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Flutter ↔ Android 通道。
 * 使用 FlutterFragmentActivity：local_auth（BiometricPrompt）要求 FragmentActivity。
 */
class MainActivity : FlutterFragmentActivity() {
    private var channel: MethodChannel? = null
    private var pendingCloneId: String? = null

    /** 等待 Activity 结果的 Flutter 调用（每个请求码最多一个）。 */
    private val pending = HashMap<Int, MethodChannel.Result>()
    private var pendingSaveContent: String? = null
    private var pendingWorkOp: Triple<String, String, Boolean>? = null // op, pkg, flag
    private var mockCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 从“最近任务”重新进入时系统会重放旧 Intent，不应再次打开分身。
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState != null || fromHistory) pendingCloneId = null
        requestHighRefreshRate()
        // 工作资料中的实例：确保跨资料入口已就绪（幂等）
        try {
            CloneAdminReceiver.setup(this)
        } catch (_: Exception) {
        }
        // 资料内：若只是被拉起做初始化且带了 NO_ANIMATION 标记的 setup，可直接退出
        if (intent?.getBooleanExtra("ps_setup_only", false) == true && WorkProfile.dpm(this).isProfileOwnerApp(packageName)) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        pendingCloneId = if (fromHistory) null else intent?.getStringExtra(EXTRA_CLONE_ID)
        val ch = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel = ch
        val cb: () -> Unit = { runOnUiThread { channel?.invokeMethod("mockChanged", null) } }
        mockCallback = cb
        MockLocationService.onStateChanged = cb
        Shortcuts.onPinned = { sid -> runOnUiThread { channel?.invokeMethod("pinResult", sid) } }
        ch.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getLaunchCloneId" -> {
                        result.success(pendingCloneId)
                        pendingCloneId = null
                    }
                    "pinShortcut" -> result.success(
                        Shortcuts.pin(
                            this, call.argument<String>("id")!!, call.argument<String>("pkg")!!,
                            call.argument<String>("label")!!, call.argument<Boolean>("force") ?: false,
                        )
                    )
                    "pinLegacy" -> result.success(
                        Shortcuts.pinLegacy(this, call.argument<String>("id")!!, call.argument<String>("pkg")!!, call.argument<String>("label")!!)
                    )
                    "isPinned" -> result.success(Shortcuts.isPinned(this, call.argument<String>("sid")!!))
                    "openShortcutPermission" -> result.success(Shortcuts.openShortcutPermission(this))
                    // ---- 厂商适配 ----
                    "romInfo" -> result.success(
                        mapOf(
                            "rom" to OemAdapter.rom,
                            "romLabel" to OemAdapter.label,
                            "shortcutPerm" to OemAdapter.shortcutPermission(this),
                            "shortcutNeedsPermission" to OemAdapter.shortcutNeedsPermission,
                            "legacyShortcut" to OemAdapter.supportsLegacyShortcut,
                            "hasAutoStart" to OemAdapter.hasAutoStartPage(this),
                            "ignoringBattery" to OemAdapter.isIgnoringBattery(this),
                        )
                    )
                    "openAutoStart" -> result.success(OemAdapter.launchFirst(this, OemAdapter.autoStartIntents(this)))
                    "requestIgnoreBattery" -> result.success(OemAdapter.requestIgnoreBattery(this))
                    "isIgnoringBattery" -> result.success(OemAdapter.isIgnoringBattery(this))
                    "highRefreshRate" -> {
                        requestHighRefreshRate()
                        result.success(null)
                    }
                    // ---- 虚拟定位 ----
                    "startMock" -> result.success(startMock(call.argument<Double>("lat")!!, call.argument<Double>("lng")!!))
                    "updateMock" -> {
                        MockLocationService.update(
                            this,
                            call.argument<Double>("lat")!!,
                            call.argument<Double>("lng")!!,
                            (call.argument<Double>("bearing") ?: 0.0).toFloat(),
                            (call.argument<Double>("speed") ?: 0.0).toFloat(),
                        )
                        result.success(null)
                    }
                    "stopMock" -> {
                        stopService(Intent(this, MockLocationService::class.java))
                        result.success("ok")
                    }
                    "isMocking" -> result.success(MockLocationService.running)
                    "openDevSettings" -> {
                        openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        result.success(null)
                    }
                    // ---- 工作资料 ----
                    "workStatus" -> result.success(WorkProfile.status(this))
                    "provisionWork" -> provisionWork(result)
                    "repairWorkLink" -> result.success(WorkProfile.repairLink(this, this))
                    "virtualStatus" -> result.success(VirtualEngine.status())
                    "retryVirtualEngine" -> result.success(VirtualEngine.retry(this))
                    "listVirtualClones" -> result.success(VirtualEngine.listCloned())
                    "cloneVirtual" -> result.success(VirtualEngine.clone(call.argument<String>("pkg")!!))
                    "launchVirtual" -> result.success(VirtualEngine.launch(call.argument<String>("pkg")!!))
                    "deleteVirtual" -> result.success(VirtualEngine.delete(call.argument<String>("pkg")!!))
                    "isVirtualCloned" -> result.success(VirtualEngine.isCloned(call.argument<String>("pkg")!!))
                    "canInstallPackages" -> result.success(canInstallPackages())
                    "openUnknownAppSources" -> result.success(openUnknownAppSources())
                    "installApk" -> installApk(result, call.argument<String>("path")!!)
                    "isInsideWorkProfile" -> result.success(WorkProfile.dpm(this).isProfileOwnerApp(packageName))
                    "fetchUpdateManifestViaWeb" -> ManifestWebFetcher.fetch(this, call.argument<String>("url") ?: "", result)

                    "requestInteractAcrossProfiles" -> result.success(WorkProfile.requestInteract(this))
                    "openPersonalSpace" -> result.success(openPersonalSpace())
                    "openWorkProfileSettings" -> result.success(openWorkProfileSettings())
                    "wipeWorkProfile" -> wipeWorkProfile(result)
                    "listWorkApps" -> background(result) { WorkProfile.workApps(this) }
                    "listPersonalApps" -> background(result) { WorkProfile.personalApps(this) }
                    "launchWorkApp" -> result.success(WorkProfile.launch(this, call.argument<String>("pkg")!!))
                    "isInWork" -> result.success(WorkProfile.isInWork(this, call.argument<String>("pkg")!!))
                    "unpauseWork" -> result.success(unpauseWork())
                    "icons" -> {
                        val pkgs = call.argument<List<String>>("pkgs") ?: emptyList()
                        val work = call.argument<Boolean>("work") ?: false
                        val size = (call.argument<Int>("size") ?: 144).coerceIn(48, 256)
                        background(result) { pkgs.associateWith { WorkProfile.iconPng(this, it, work, size) } }
                    }
                    "cloneIntoWork" -> workOp(result, WorkOpActivity.OP_CLONE, call.argument<String>("pkg")!!, false)
                    "hideWorkApp" -> workOp(result, WorkOpActivity.OP_HIDE, call.argument<String>("pkg")!!, false)
                    // ---- 用量 ----
                    "hasUsageAccess" -> result.success(UsageHelper.hasAccess(this))
                    "openUsageSettings" -> {
                        openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        result.success(null)
                    }
                    "usageToday" -> background(result) {
                        UsageHelper.today(this, WorkProfile.personalApps(this).map { m -> mapOf("pkg" to m["pkg"] as String, "label" to m["label"] as String) })
                    }
                    // ---- 文件 ----
                    "saveFile" -> saveFile(result, call.argument<String>("name")!!, call.argument<String>("content")!!)
                    "shareFile" -> result.success(shareFile(call.argument<String>("name")!!, call.argument<String>("content")!!))
                    "openFile" -> openFile(result)
                    // ---- 小组件 ----
                    "updateWidget" -> {
                        @Suppress("UNCHECKED_CAST")
                        CloneWidgetProvider.save(this, (call.argument<List<Map<String, Any?>>>("items") ?: emptyList()))
                        result.success(null)
                    }
                    else -> if (!ToolboxController.handle(this, call, result)) result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("native_error", e.message, null)
            }
        }
    }

    /**
     * 高刷新率：选择与当前分辨率相同、刷新率最高的显示模式（部分 ROM 默认把第三方应用限制在 60Hz）。
     */
    private fun requestHighRefreshRate() {
        try {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            d ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (best.modeId != window.attributes.preferredDisplayModeId) {
                window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (MockLocationService.onStateChanged === mockCallback) MockLocationService.onStateChanged = null
        Shortcuts.onPinned = null
        io.shutdown()
        // 未完成的请求：返回取消，避免 Dart 端 Future 永不完成
        for (r in pending.values) r.success(null)
        pending.clear()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CLONE_ID)?.let { channel?.invokeMethod("openClone", it) }
    }

    // ---------------- 后台执行（图标渲染 / 应用列表不阻塞 UI 线程） ----------------
    private val io = java.util.concurrent.Executors.newFixedThreadPool(2)

    private fun background(result: MethodChannel.Result, work: () -> Any?) {
        io.execute {
            val r = try {
                Result.success(work())
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                r.fold({ result.success(it) }, { result.error("native_error", it.message, null) })
            }
        }
    }

    // ---------------- 虚拟定位 ----------------
    private fun startMock(lat: Double, lng: Double): String {
        if (!lat.isFinite() || !lng.isFinite() || Math.abs(lat) > 90 || Math.abs(lng) > 180) return "error"
        // 先同步尝试注册测试 Provider，以便立即识别“未被选为模拟位置应用”。
        try {
            MockLocationService.ensureProviders(this)
        } catch (e: SecurityException) {
            return "not_mock_app"
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val i = Intent(this, MockLocationService::class.java).putExtra("lat", lat).putExtra("lng", lng)
        ContextCompat.startForegroundService(this, i)
        return "ok"
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------------- 工作资料 ----------------
    private val um get() = getSystemService(Context.USER_SERVICE) as UserManager

    /**
     * 'started'（结果稍后通过同一次调用返回 'ok' / 'cancelled'）不再使用：直接等待向导结果。
     * 返回 'ok' | 'cancelled' | 'exists' | 'unsupported' | 'not_allowed' | 'foreign' | 'error'
     */
    private fun provisionWork(result: MethodChannel.Result) {
        val st = WorkProfile.status(this)
        when (st["state"]) {
            "ready", "paused", "inside" -> return result.success("exists")
            "foreign" -> return result.success("foreign")
            "unsupported" -> return result.success("unsupported")
        }
        val dpm = WorkProfile.dpm(this)
        if (!dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) return result.success("not_allowed")
        val i = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
            ComponentName(this, CloneAdminReceiver::class.java),
        )
        if (i.resolveActivity(packageManager) == null) return result.success("unsupported")
        pending.remove(REQ_PROVISION)?.success("cancelled")
        try {
            pending[REQ_PROVISION] = result
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PROVISION)
        } catch (e: Exception) {
            pending.remove(REQ_PROVISION)
            result.success("error")
        }
    }

    private fun unpauseWork(): String {
        val u = WorkProfile.ourUser(this) ?: return "no_profile"
        if (!um.isQuietModeEnabled(u)) return "ok"
        if (Build.VERSION.SDK_INT < 28) return "unsupported"
        return try {
            um.requestQuietModeEnabled(false, u)
            "requested"
        } catch (_: Exception) {
            "error"
        }
    }

        /**
     * 在工作资料中执行操作。优先 CrossProfileApps → ProfileOpActivity（不依赖 IntentFilter），
     * 再回退旧版 WORK_OP 转发；都以系统实际状态（isInWork 等）轮询核对，不再卡在「请稍后重试」。
     */
    private fun workOp(result: MethodChannel.Result, op: String, pkg: String, flag: Boolean) {
        if (WorkProfile.ourUser(this) == null) {
            result.success("no_profile")
            return
        }
        if (op == WorkOpActivity.OP_CLONE && Build.VERSION.SDK_INT < 28) {
            result.success("unsupported")
            return
        }
        val profileOp = when (op) {
            WorkOpActivity.OP_CLONE -> ProfileOpActivity.OP_CLONE
            WorkOpActivity.OP_HIDE -> ProfileOpActivity.OP_HIDE
            WorkOpActivity.OP_SUSPEND -> ProfileOpActivity.OP_SUSPEND
            WorkOpActivity.OP_WIPE -> ProfileOpActivity.OP_WIPE
            else -> op
        }
        val apk = if (op == WorkOpActivity.OP_CLONE) WorkProfile.apkPath(this, pkg) else null
        // 1) CrossProfileApps（主路径）
        if (WorkProfile.startOpOk(this, profileOp, pkg, flag, apk)) {
            pendingWorkOp = Triple(op, pkg, flag)
            pollWorkOp(result, Triple(op, pkg, flag), 0)
            return
        }
        // 2) 旧版跨资料 IntentFilter
        val i = Intent(WorkOpActivity.ACTION).putExtra(WorkOpActivity.EXTRA_OP, op)
            .putExtra(WorkOpActivity.EXTRA_PKG, pkg).putExtra(WorkOpActivity.EXTRA_FLAG, flag)
        val req = when (op) {
            WorkOpActivity.OP_CLONE -> REQ_WORK_CLONE
            WorkOpActivity.OP_HIDE -> REQ_WORK_HIDE
            else -> REQ_WORK_SUSPEND
        }
        pending.remove(req)?.success("error")
        try {
            pendingWorkOp = Triple(op, pkg, flag)
            pending[req] = result
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (e: ActivityNotFoundException) {
            pending.remove(req)
            // 禁止再 startMainActivity！只再试一次静默 ProfileOpActivity
            if (WorkProfile.startOpOk(this, profileOp, pkg, flag, apk)) {
                pendingWorkOp = Triple(op, pkg, flag)
                pollWorkOp(result, Triple(op, pkg, flag), 0)
            } else {
                result.success("need_setup")
            }
        } catch (e: SecurityException) {
            pending.remove(req)
            result.success("not_owner")
        }
    }

    /** 轮询工作资料实际状态；started=false 表示操作可能没发出去。 */
    private fun pollWorkOp(result: MethodChannel.Result, op: Triple<String, String, Boolean>, attempt: Int, started: Boolean = true) {
        window.decorView.postDelayed({
            if (verifyWorkOp(op)) {
                pendingWorkOp = null
                result.success("ok")
                return@postDelayed
            }
            if (attempt >= 20) { // ~10s
                pendingWorkOp = null
                result.success(
                    when {
                        !started -> "need_setup"
                        op.first == WorkOpActivity.OP_CLONE -> "rejected"
                        else -> "error"
                    },
                )
                return@postDelayed
            }
            pollWorkOp(result, op, attempt + 1, started)
        }, 500)
    }

    /** 工作资料内实例：切回个人空间的平行空间（CrossProfileApps，同包名跨资料启动）。 */
    private fun openPersonalSpace(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val cpa = getSystemService(android.content.pm.CrossProfileApps::class.java)
                val target = cpa.targetUserProfiles.firstOrNull()
                if (target != null) {
                    cpa.startMainActivity(ComponentName(this, MainActivity::class.java), target)
                    finish()
                    return "ok"
                }
            } catch (_: Exception) {
            }
        }
        finish()
        return "closed"
    }

    /** 系统“账号 / 工作资料”设置页（删除其他工作资料）。 */
    private fun openWorkProfileSettings(): String {
        for (a in listOf(Settings.ACTION_SYNC_SETTINGS, "android.settings.MANAGED_PROFILE_SETTINGS", Settings.ACTION_SETTINGS)) {
            try {
                val i = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(packageManager) != null) {
                    startActivity(i)
                    return a
                }
            } catch (_: Exception) {
            }
        }
        return "error"
    }

    /** 删除工作资料：由资料内的资料所有者执行 wipeData；入口不可用时打开系统设置。 */
    private fun wipeWorkProfile(result: MethodChannel.Result) {
        if (WorkProfile.ourUser(this) == null) return result.success("no_profile")
        if (WorkProfile.startOpOk(this, ProfileOpActivity.OP_WIPE)) {
            pending[REQ_WIPE] = result
            window.decorView.postDelayed({
                WorkProfile.invalidate()
                val r = pending.remove(REQ_WIPE) ?: return@postDelayed
                r.success(if (WorkProfile.ourUser(this) == null) "ok" else "pending")
            }, 3000)
            return
        }
        val i = Intent(WorkOpActivity.ACTION).putExtra(WorkOpActivity.EXTRA_OP, WorkOpActivity.OP_WIPE)
        pending.remove(REQ_WIPE)?.success("error")
        try {
            pending[REQ_WIPE] = result
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_WIPE)
        } catch (_: Exception) {
            pending.remove(REQ_WIPE)
            WorkProfile.repairLink(this, this)
            openWorkProfileSettings()
            result.success("settings")
        }
    }

    /** 部分系统的跨资料转发不回传结果：直接核对工作资料中的实际状态。 */
    private fun verifyWorkOp(op: Triple<String, String, Boolean>): Boolean = try {
        when (op.first) {
            WorkOpActivity.OP_CLONE -> WorkProfile.isInWork(this, op.second)
            WorkOpActivity.OP_HIDE -> !WorkProfile.isInWork(this, op.second)
            WorkOpActivity.OP_SUSPEND -> WorkProfile.isSuspendedInWork(this, op.second) == op.third
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    // ---------------- 文件 ----------------
    private fun saveFile(result: MethodChannel.Result, name: String, content: String) {
        pending.remove(REQ_SAVE)?.success("cancelled")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
        try {
            pendingSaveContent = content
            pending[REQ_SAVE] = result
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SAVE)
        } catch (e: ActivityNotFoundException) {
            pending.remove(REQ_SAVE)
            pendingSaveContent = null
            result.success("error")
        }
    }

    private fun shareFile(name: String, content: String): String {
        val dir = File(cacheDir, "backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 只保留最新一份
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val f = File(dir, safe).apply { writeText(content, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, safe)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "分享备份").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return "ok"
    }

    private fun openFile(result: MethodChannel.Result) {
        pending.remove(REQ_OPEN)?.success(null)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        try {
            pending[REQ_OPEN] = result
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_OPEN)
        } catch (e: ActivityNotFoundException) {
            pending.remove(REQ_OPEN)
            result.success(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val r = pending.remove(requestCode) ?: return
        try {
            when (requestCode) {
                REQ_SAVE -> {
                    val content = pendingSaveContent
                    pendingSaveContent = null
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null || content == null) {
                        r.success("cancelled")
                    } else {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                            ?: throw IllegalStateException("无法写入")
                        r.success("ok")
                    }
                }
                REQ_OPEN -> {
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null) {
                        r.success(null)
                    } else {
                        val bytes = contentResolver.openInputStream(uri)?.use { s ->
                            val buf = ByteArray(MAX_IMPORT + 1)
                            var n = 0
                            while (n < buf.size) {
                                val k = s.read(buf, n, buf.size - n)
                                if (k < 0) break
                                n += k
                            }
                            buf.copyOf(n)
                        } ?: ByteArray(0)
                        // 超过上限时返回截断内容，Dart 端校验会判定为无效
                        r.success(String(bytes, Charsets.UTF_8))
                    }
                }
                REQ_PROVISION -> {
                    WorkProfile.invalidate()
                    if (resultCode == Activity.RESULT_OK) WorkProfile.rememberOwned(this)
                    r.success(if (resultCode == Activity.RESULT_OK) "ok" else "cancelled")
                }
                REQ_WIPE -> {
                    WorkProfile.invalidate()
                    r.success(if (WorkProfile.ourUser(this) == null) "ok" else "pending")
                }
                REQ_WORK_CLONE, REQ_WORK_SUSPEND, REQ_WORK_HIDE -> {
                    val op = pendingWorkOp
                    pendingWorkOp = null
                    val explicit = data?.getStringExtra(WorkOpActivity.EXTRA_RESULT)
                    // 以系统实际状态为准（安装是异步的，结果 "ok" 后再核对一次）
                    r.success(
                        when {
                            op != null && verifyWorkOp(op) -> "ok"
                            explicit != null && explicit != "ok" -> explicit
                            explicit == "ok" || resultCode == Activity.RESULT_OK -> "ok"
                            else -> "not_owner"
                        }
                    )
                }
                else -> r.success(null)
            }
        } catch (e: Exception) {
            r.success(if (requestCode == REQ_OPEN) null else "error")
        }
    }


    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) packageManager.canRequestPackageInstalls() else true

    private fun openUnknownAppSources(): String {
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "ok"
            } else "unsupported"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    private fun installApk(result: MethodChannel.Result, path: String) {
        try {
            val f = java.io.File(path)
            if (!f.isFile) {
                result.success("missing")
                return
            }
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                result.success("need_permission")
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            result.success("ok")
        } catch (e: Exception) {
            result.success("error:${e.javaClass.simpleName}:${e.message}")
        }
    }


    companion object {
        const val CHANNEL = "parallel_space/native"
        const val EXTRA_CLONE_ID = "clone_id"
        private const val REQ_PROVISION = 41
        private const val REQ_SAVE = 42
        private const val REQ_OPEN = 43
        private const val REQ_WORK_CLONE = 44
        private const val REQ_WORK_SUSPEND = 45
        private const val REQ_WORK_HIDE = 46
        private const val REQ_WIPE = 47
        private const val MAX_IMPORT = 2 * 1024 * 1024

        /** 快捷方式 / 小组件共用的分身深链 Intent。 */
        fun cloneLaunchIntent(ctx: Context, id: String): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = "parallelspace://clone/$id".toUri() // 让每个分身的 PendingIntent 互不覆盖
                putExtra(EXTRA_CLONE_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
