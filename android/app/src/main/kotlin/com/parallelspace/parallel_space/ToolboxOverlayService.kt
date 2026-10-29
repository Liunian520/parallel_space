package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 分身在前台时显示的可拖动、可收起悬浮窗。
 *
 * 1.3.15：展开改为 MATCH_PARENT 全屏窗口 + 透明遮罩点外收起，去掉
 * FLAG_NOT_TOUCH_MODAL / FLAG_WATCH_OUTSIDE_TOUCH（HyperOS 上这两项配合
 * updateViewLayout 改尺寸后，触控区域常不更新，整块面板含「返回」都点不动，
 * 而气泡在收起态可点）。展开/收起会 remove+addView 重建输入通道。
 * 根布局 dispatchTouchEvent 打点；每次 updateViewLayout 记 flags。
 * 1.3.12：只读密码本；保留 tick 防抖、AppOps、START_STICKY、看门狗。
 * 1.3.18：密码本列出全部账号（备注名），点进详情再复制/一键填入；不限当前分身。
 */
class ToolboxOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor { r ->
        Thread(r, "toolbox-overlay").apply { isDaemon = true }
    }
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var bubble: View? = null
    /** 展开时全屏透明遮罩：点面板外收起；避免 WATCH_OUTSIDE。 */
    private var scrim: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var mode = Mode.TOOLS
    /** 密码本二级页：正在查看的条目 id；空表示在备注名列表。 */
    private var vaultDetailId: String = ""
    private var pkg = ""
    /** 当前分身实例 id（w:包名 / w:包名#userId），用于密码本按实例关联。 */
    private var uid = ""
    private var launchedAt = 0L
    private var lastSeenClientAt = 0L
    private var resolvedLabel: String = ""
    private var resolvedIcon: Drawable? = null
    private val vaultCache = AtomicReference<List<VaultStore.Entry>>(emptyList())
    private var lastWatchLogAt = 0L
    /** 视图代数：removeView / 重建时递增，避免旧动画回调整坏新窗。 */
    private var viewGen = 0
    private var edgeAnimator: android.animation.ValueAnimator? = null
    private var vaultReceiver: BroadcastReceiver? = null
    private var vaultObserver: android.os.FileObserver? = null
    /** tick 连续读到 enabled/overlay 否定的次数；单次瞬假绝不 stopSelf。 */
    private var denyStreak = 0
    private var denySince = 0L
    /** 收起时的气泡坐标；展开面板时可能把 x/y 挪回屏内，收起要还原。 */
    private var bubbleX = 0
    private var bubbleY = 0
    private var expandedAt = 0L
    private var vaultChangedListener: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                tickOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ToolboxCrashLog.installHandler(this, "overlay")
        ToolboxCrashLog.append(this, "INFO", "overlay onCreate sdk=${Build.VERSION.SDK_INT}")
        registerVaultListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_USER_STOP) {
            ToolboxCrashLog.append(this, "INFO", "stopSelf reason=user_stop_intent")
            stopSelf()
            return START_NOT_STICKY
        }
        val incoming = intent?.getStringExtra("pkg")?.takeIf { it.isNotBlank() }
        if (incoming != null) {
            pkg = incoming
            uid = intent?.getStringExtra("uid")?.takeIf { it.isNotBlank() } ?: CloneId.of(incoming, 0)
            launchedAt = SystemClock.elapsedRealtime()
            lastSeenClientAt = launchedAt
            resolveAppInfoAsync(pkg)
            ToolboxCrashLog.append(this, "INFO", "start pkg=$pkg")
        } else if (pkg.isBlank()) {
            pkg = ToolboxController.lastPackage(this)
            uid = ToolboxController.lastUid(this).ifBlank { if (pkg.isNotBlank()) CloneId.of(pkg, 0) else "" }
            if (pkg.isNotBlank()) resolveAppInfoAsync(pkg)
            ToolboxCrashLog.append(this, "INFO", "restart sticky pkg=$pkg flags=$flags")
        }
        if (launchedAt == 0L) launchedAt = SystemClock.elapsedRealtime()
        // 仅在明确关闭时停；悬浮窗权限瞬假不在 onStart 拆服务（交给 tick 防抖）
        if (!ToolboxController.enabled(this)) {
            ToolboxCrashLog.append(this, "WARN", "stop onStart reason=enabled_false")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
            ToolboxCrashLog.append(this, "ERROR", "startForeground failed", t)
            // Android 14+ 前台服务限制：仍尽量保留服务，下一拍再试；不要立刻死亡导致气泡永久消失
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), 0)
            } catch (t2: Throwable) {
                ToolboxCrashLog.append(this, "ERROR", "startForeground fallback failed", t2)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ToolboxController.onVaultAuthedListener = { handler.post { rebuild("vault_authed") } }
        handler.removeCallbacks(tick)
        handler.post(tick)
        // 系统杀进程后以 null intent 重启时，立刻尝试恢复气泡
        handler.post { ensureView(force = root == null || root?.isAttachedToWindow != true) }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 主界面被滑掉不等于分身结束；保持服务，由 tick 决定何时停。
        super.onTaskRemoved(rootIntent)
        if (ToolboxController.enabled(this) && OemAdapter.canDrawOverlay(this)) {
            try {
                val i = Intent(this, ToolboxOverlayService::class.java)
                if (pkg.isNotBlank()) i.putExtra("pkg", pkg)
                if (uid.isNotBlank()) i.putExtra("uid", uid)
                startForegroundServiceCompat(i)
            } catch (t: Throwable) {
                Log.w(TAG, "re-schedule after onTaskRemoved failed", t)
            }
        }
    }

    override fun onDestroy() {
        ToolboxCrashLog.append(this, "INFO", "overlay onDestroy pkg=$pkg")
        handler.removeCallbacks(tick)
        cancelIdleDim()
        try {
            edgeAnimator?.cancel()
        } catch (_: Throwable) {
        }
        edgeAnimator = null
        unregisterVaultListener()
        if (ToolboxController.onVaultAuthedListener != null) ToolboxController.onVaultAuthedListener = null
        removeView()
        // 不 shutdownNow：sticky 重启可能复用同一进程内的排队任务；让空闲守护线程自然回收
        super.onDestroy()
    }

    private fun tickOnce() {
        if (maybeStopForDeny()) return
        val now = SystemClock.elapsedRealtime()
        val client = anyClient()
        if (client) lastSeenClientAt = now

        val wantShow = shouldShow(now, client)
        if (wantShow) {
            // 看门狗：该显示时若气泡丢了（addView 失败 / 被系统摘掉），重新挂上
            if (root == null || root?.isAttachedToWindow != true) {
                logWatch("bubble missing while wantShow; re-adding client=$client")
                ToolboxCrashLog.append(this, "WARN", "watchdog re-add bubble client=$client attached=${root?.isAttachedToWindow}")
                ensureView(force = true)
            } else {
                ensureView(force = false)
            }
            return
        }

        // 面板展开或编辑 Activity 打开：绝不因「分身不可见」拆窗
        if (expanded) {
            ensureView(force = root == null || root?.isAttachedToWindow != true)
            return
        }

        val quietFor = now - lastSeenClientAt.coerceAtLeast(launchedAt)
        if (quietFor > STOP_AFTER_MS && !client) {
            logWatch("no clone for ${quietFor}ms; stopSelf")
            ToolboxCrashLog.append(this, "INFO", "stopSelf reason=quiet quiet=${quietFor}ms")
            stopSelf()
            return
        }
        // 分身短暂切后台：只收起面板，保留气泡；超过 HIDE_AFTER 再摘气泡，服务仍活着方便回来
        // HyperOS 上进程列表经常空，quiet 计时用 STICKY 护着，不会刚开分身就被摘掉
        if (quietFor > HIDE_AFTER_MS) {
            if (root != null) {
                logWatch("clone quiet ${quietFor}ms; hide bubble (service stays)")
                hideOverlay("tick_hide quiet=${quietFor}ms")
            }
        }
    }

    /**
     * 防抖停服：单次 enabled / canDrawOverlays 否定绝不 stopSelf。
     * 需连续 >=3 次且跨度 >=10s，且未钉住编辑 Activity；overlay 须 Settings 与 AppOps 双否。
     * 用户关闭走 [ACTION_USER_STOP]，不依赖本路径。
     * @return true 表示本拍已处理完（含已 stop），tick 应 return
     */
    private fun maybeStopForDeny(): Boolean {
        val enabled = ToolboxController.enabled(this)
        val settingsOverlay = OemAdapter.settingsCanDrawOverlay(this)
        val appOpsOverlay = OemAdapter.overlayAppOpsAllowed(this)
        val overlayOk = settingsOverlay || appOpsOverlay
        if (enabled && overlayOk) {
            if (denyStreak != 0) {
                ToolboxCrashLog.append(
                    this,
                    "INFO",
                    "tick deny cleared after #$denyStreak enabled=$enabled settings=$settingsOverlay appOps=$appOpsOverlay",
                )
            }
            denyStreak = 0
            denySince = 0L
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (denyStreak == 0) denySince = now
        denyStreak++
        val elapsed = now - denySince
        ToolboxCrashLog.append(
            this,
            "WARN",
            "tick deny #$denyStreak elapsed=${elapsed}ms enabled=$enabled settingsOverlay=$settingsOverlay appOpsOverlay=$appOpsOverlay pinned=false expanded=$expanded",
        )
        // 编辑页打开或面板展开时：只记日志，绝不因瞬假停服
        if (expanded) return false
        if (denyStreak < DENY_STREAK_MIN || elapsed < DENY_WINDOW_MS) return false

        // 再读一次，避免阈值刚过就停在抖动上
        val enabled2 = ToolboxController.enabled(this)
        val settings2 = OemAdapter.settingsCanDrawOverlay(this)
        val appOps2 = OemAdapter.overlayAppOpsAllowed(this)
        if (enabled2 && (settings2 || appOps2)) {
            ToolboxCrashLog.append(
                this,
                "INFO",
                "tick deny recovered on recheck enabled=$enabled2 settings=$settings2 appOps=$appOps2",
            )
            denyStreak = 0
            denySince = 0L
            return false
        }
        val reason = when {
            !enabled2 -> "enabled_false"
            !settings2 && !appOps2 -> "overlay_clearly_denied"
            else -> "deny_debounce"
        }
        ToolboxCrashLog.append(
            this,
            "WARN",
            "tick stopSelf after debounce reason=$reason enabled=$enabled2 settings=$settings2 appOps=$appOps2 streak=$denyStreak elapsed=${elapsed}ms",
        )
        stopSelf()
        return true
    }

    private fun shouldShow(now: Long = SystemClock.elapsedRealtime(), client: Boolean = anyClient()): Boolean {
        if (!ToolboxController.enabled(this) || !OemAdapter.canDrawOverlay(this)) return false
        if (clientVisible()) return true
        if (client) return true // 有分身进程就保留，避免焦点切到悬浮窗后被误判为不可见
        // HyperOS 常让 getRunningAppProcesses 看不到 :pN；启动后 STICKY_MS 内即使列表空也继续显示
        if (pkg.isNotBlank()) {
            val sinceLaunch = now - launchedAt
            val sinceSeen = now - lastSeenClientAt.coerceAtLeast(launchedAt)
            if (sinceLaunch < STICKY_MS || sinceSeen < STICKY_MS) return true
        }
        return now - launchedAt < GRACE_MS && pkg.isNotBlank()
    }

    private fun clientVisible(): Boolean = processes().any { p ->
        isClientProcess(p.processName) &&
            p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun anyClient(): Boolean {
        if (processes().any { isClientProcess(it.processName) }) return true
        // HyperOS / Android 14+：ActivityManager 经常不返回同 UID 的 :pN，改扫 /proc
        return anyClientViaProc()
    }

    private fun isClientProcess(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        // :p0 … :pN 分身；也认 black 守护（证明引擎还活着，避免误杀）
        return name.startsWith("$packageName:p") || name == "$packageName:black" ||
            name.startsWith("$packageName:black")
    }

    private fun processes(): List<ActivityManager.RunningAppProcessInfo> = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 通过 /proc/<pid>/cmdline 找同包 :pN（同 UID 可读）。 */
    private fun anyClientViaProc(): Boolean {
        val prefix = "$packageName:p"
        return try {
            val proc = java.io.File("/proc")
            val dirs = proc.listFiles() ?: return false
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                if (dir.name.toIntOrNull() == null) continue
                val bytes = try {
                    java.io.File(dir, "cmdline").inputStream().use { it.readBytes() }
                } catch (_: Throwable) {
                    continue
                }
                if (bytes.isEmpty()) continue
                val end = bytes.indexOf(0)
                val name = String(bytes, 0, if (end >= 0) end else bytes.size, Charsets.UTF_8)
                if (name.startsWith(prefix)) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView(force: Boolean) {
        try {
            ensureViewInner(force)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureView failed", t)
            ToolboxCrashLog.append(this, "ERROR", "ensureView failed", t)
            root = null
            card = null
            bubble = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureViewInner(force: Boolean) {
        if (root != null && root?.isAttachedToWindow == true && !force) return
        if (force && root != null) {
            ToolboxCrashLog.append(this, "INFO", "ensureView force re-add reason=watchdog_or_attach mode=$mode pinned=false")
            removeView()
        }
        if (root != null) return
        val window = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = window
        val pal = Palette(this)
        val bubbleSize = dp(48)
        val bubbleView = FrameLayout(this).apply {
            background = bubbleDrawable(pal, idle = false)
            elevation = dp(6).toFloat()
            contentDescription = "工具箱"
            alpha = 1f
            val icon = ImageView(this@ToolboxOverlayService).apply {
                setImageResource(R.drawable.ic_toolbox)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
            }
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        bubble = bubbleView
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(pal)
            elevation = dp(10).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            // 1.3.15：不要 alpha=0 起步——部分机型半透明视图不收触摸
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            isClickable = true
            isFocusable = false
        }
        card = panel
        val scrimView = View(this).apply {
            // 近乎全透明但仍参与命中（避免 OEM 把全 0 alpha 当穿透）
            setBackgroundColor(0x01000000)
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            contentDescription = "关闭工具箱面板"
            setOnClickListener {
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap scrim collapse")
                collapse()
            }
        }
        scrim = scrimView
        val holder = TouchLogFrameLayout(this).apply {
            // 根布局给一层极淡底，避免 HyperOS 按「根像素全透明」把整窗触控区域算空
            setBackgroundColor(0x01000000)
        }
        holder.addView(
            scrimView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        holder.addView(bubbleView, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        holder.addView(
            panel,
            FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        root = holder
        val prefs = getSharedPreferences("toolbox", Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            bubbleFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", screenWidth() - bubbleSize - dp(12))
            y = prefs.getInt("y", dp(160))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            // 明确不要 NOT_TOUCHABLE
            dimAmount = 0f
        }
        lp = params
        bubbleX = params.x
        bubbleY = params.y
        bubbleView.setOnTouchListener(dragListener { toggle() })
        try {
            window.addView(holder, params)
            logWindow("addView_bubble", params)
            applyVisibility()
            scheduleIdleDim()
            ToolboxCrashLog.append(this, "INFO", "addView ok x=${params.x} y=${params.y} pkg=$pkg flags=${flagString(params.flags)}")
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
            ToolboxCrashLog.append(this, "ERROR", "addView failed", t)
            root = null
            card = null
            bubble = null
            scrim = null
            // 下一 tick 看门狗会再试
        }
    }

    private fun hideOverlay(reason: String) {
        ToolboxCrashLog.append(
            this,
            "INFO",
            "hideOverlay reason=$reason expanded=$expanded mode=$mode",
        )
        removeView()
    }

    private fun removeView() {
        viewGen++
        cancelIdleDim()
        try {
            edgeAnimator?.cancel()
        } catch (_: Throwable) {
        }
        edgeAnimator = null
        val view = root ?: return
        val b = bubble
        val c = card
        root = null
        card = null
        bubble = null
        scrim = null
        try {
            b?.animate()?.cancel()
        } catch (_: Throwable) {
        }
        try {
            c?.animate()?.cancel()
        } catch (_: Throwable) {
        }
        try {
            if (view.isAttachedToWindow) wm?.removeView(view)
            else wm?.removeViewImmediate(view)
        } catch (_: Throwable) {
            try {
                wm?.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        try {
            val params = lp
            if (params != null) {
                bubbleX = params.x
                bubbleY = params.y
            }
            expanded = true
            expandedAt = SystemClock.elapsedRealtime()
            cancelIdleDim()
            bubble?.animate()?.cancel()
            bubble?.alpha = 1f
            card?.animate()?.cancel()
            card?.alpha = 1f
            card?.scaleX = 1f
            card?.scaleY = 1f
            applyVisibility()
            refreshVaultAsync()
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "expand failed", t)
        }
    }

    private fun collapse() {
        try {
            expanded = false
            mode = Mode.TOOLS
            card?.animate()?.cancel()
            applyVisibility()
            scheduleIdleDim()
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "collapse failed", t)
        }
    }

    /**
     * 收起：WRAP_CONTENT 气泡窗。展开：MATCH_PARENT 全屏窗 + 透明遮罩 + 面板。
     * 1.3.15 起切换时 remove + addView 重建，强制系统刷新输入区域（HyperOS 上
     * 只 updateViewLayout 改尺寸/flags 时，触控区域会停留在旧的 48dp 气泡）。
     */
    private fun applyVisibility() {
        try {
            val params = lp ?: return
            val view = root ?: return
            bubble?.visibility = if (expanded) View.GONE else View.VISIBLE
            card?.visibility = if (expanded) View.VISIBLE else View.GONE
            scrim?.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
                params.flags = expandedFlags()
                rebuild("apply_visibility")
                positionPanel()
            } else {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.x = bubbleX
                params.y = bubbleY
                params.flags = bubbleFlags()
            }
            params.dimAmount = 0f
            reattach(view, params, if (expanded) "expand" else "collapse")
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "applyVisibility failed", t)
        }
    }

    /** 先 removeViewImmediate 再 addView，确保输入通道按新 LayoutParams 重建。 */
    private fun reattach(view: View, params: WindowManager.LayoutParams, reason: String) {
        val w = wm ?: return
        try {
            if (view.isAttachedToWindow) {
                try {
                    w.removeViewImmediate(view)
                } catch (t: Throwable) {
                    ToolboxCrashLog.append(this, "WARN", "reattach remove failed reason=$reason", t)
                }
            }
            w.addView(view, params)
            logWindow("reattach_$reason", params)
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "reattach add failed reason=$reason", t)
            // 退回 updateViewLayout
            try {
                if (view.isAttachedToWindow) w.updateViewLayout(view, params)
                logWindow("reattach_fallback_update_$reason", params)
            } catch (t2: Throwable) {
                ToolboxCrashLog.append(this, "ERROR", "reattach fallback failed", t2)
            }
        }
    }

    /** 全屏窗口里，面板贴近气泡位置并夹在屏内。 */
    private fun positionPanel() {
        val panel = card ?: return
        try {
            val targetW = dp(320).coerceAtMost(screenWidth())
            val maxH = (screenHeight() * 0.85f).toInt().coerceAtLeast(dp(200))
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
            )
            val w = targetW
            val h = panel.measuredHeight.coerceAtLeast(dp(120)).coerceAtMost(maxH)
            val (px, py) = clampOverlayPosition(bubbleX, bubbleY, w, h, screenWidth(), screenHeight())
            val lpPanel = (panel.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT)
            lpPanel.width = w
            lpPanel.height = FrameLayout.LayoutParams.WRAP_CONTENT
            lpPanel.gravity = Gravity.TOP or Gravity.START
            lpPanel.leftMargin = px
            lpPanel.topMargin = py
            panel.layoutParams = lpPanel
            ToolboxCrashLog.append(
                this,
                "INFO",
                "positionPanel w=$w h=$h x=$px y=$py sw=${screenWidth()} sh=${screenHeight()} bubble=($bubbleX,$bubbleY)",
            )
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "positionPanel failed", t)
        }
    }

    private fun bubbleFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /** 展开：全屏 + 不抢焦点；不用 NOT_TOUCH_MODAL / WATCH_OUTSIDE。 */
    private fun expandedFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun flagString(f: Int): String {
        val parts = ArrayList<String>()
        fun has(bit: Int, n: String) { if (f and bit != 0) parts += n }
        has(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, "NOT_FOCUSABLE")
        has(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, "NOT_TOUCHABLE")
        has(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, "NOT_TOUCH_MODAL")
        has(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, "WATCH_OUTSIDE")
        has(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, "LAYOUT_IN_SCREEN")
        has(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, "HW_ACCEL")
        return "0x" + Integer.toHexString(f) + "[" + parts.joinToString("|") + "]"
    }

    private fun logWindow(tag: String, p: WindowManager.LayoutParams) {
        ToolboxCrashLog.append(
            this,
            "INFO",
            "win $tag w=${p.width} h=${p.height} x=${p.x} y=${p.y} type=${p.type} flags=${flagString(p.flags)} expanded=$expanded mode=$mode",
        )
    }

    /** 根布局：记录每个 DOWN/UP/OUTSIDE 的坐标与命中子视图，定位「点了没反应」。 */
    private inner class TouchLogFrameLayout(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val a = ev.actionMasked
            if (a == MotionEvent.ACTION_DOWN) touchActive = true
            val handled = super.dispatchTouchEvent(ev)
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                touchActive = false
                if (pendingRebuild != null) handler.postDelayed(flushRebuild, 120L)
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP ||
                a == MotionEvent.ACTION_OUTSIDE || a == MotionEvent.ACTION_CANCEL
            ) {
                val target = try {
                    findDeepestAt(this, ev.x.toInt(), ev.y.toInt())
                } catch (_: Throwable) {
                    null
                }
                val name = target?.let {
                    (it.contentDescription?.toString()?.takeIf { s -> s.isNotBlank() }
                        ?: (it as? TextView)?.text?.toString()?.take(12)
                        ?: it.javaClass.simpleName)
                } ?: "none"
                ToolboxCrashLog.append(
                    this@ToolboxOverlayService,
                    "TOUCH",
                    "root ${MotionEvent.actionToString(a)} raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) " +
                        "local=(${ev.x.toInt()},${ev.y.toInt()}) handled=$handled target=$name " +
                        "expanded=$expanded size=${width}x$height",
                )
            }
            return handled
        }
    }

    private fun findDeepestAt(v: View, x: Int, y: Int): View? {
        if (v.visibility != View.VISIBLE) return null
        if (x < 0 || y < 0 || x >= v.width || y >= v.height) return null
        if (v is android.view.ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                val cx = x - c.left + v.scrollX
                val cy = y - c.top + v.scrollY
                val hit = findDeepestAt(c, cx, cy)
                if (hit != null) return hit
            }
        }
        return if (v.isClickable) v else null
    }

    private var idleDimRunnable: Runnable? = null

    private fun scheduleIdleDim() {
        cancelIdleDim()
        if (expanded) return
        val r = Runnable {
            val b = bubble ?: return@Runnable
            if (expanded) return@Runnable
            b.animate().alpha(0.55f).setDuration(280).start()
            // 半隐到屏幕边缘
            snapToEdge(halfHide = true)
        }
        idleDimRunnable = r
        handler.postDelayed(r, 2_500L)
    }

    private fun cancelIdleDim() {
        idleDimRunnable?.let { handler.removeCallbacks(it) }
        idleDimRunnable = null
        bubble?.animate()?.cancel()
        bubble?.alpha = 1f
    }

    private fun snapToEdge(halfHide: Boolean = false) {
        val params = lp ?: return
        val view = root ?: return
        val b = bubble ?: return
        val size = b.width.takeIf { it > 0 } ?: dp(48)
        val sw = screenWidth()
        val mid = params.x + size / 2
        val left = mid < sw / 2
        // 半隐只露出 2/3，避免看起来像「气泡消失」
        val target = if (left) {
            if (halfHide) -(size / 3) else dp(8)
        } else {
            if (halfHide) sw - size * 2 / 3 else (sw - size - dp(8)).coerceAtLeast(0)
        }
        val start = params.x
        val gen = viewGen
        try {
            edgeAnimator?.cancel()
        } catch (_: Throwable) {
        }
        val anim = android.animation.ValueAnimator.ofInt(start, target)
        edgeAnimator = anim
        anim.duration = 220
        anim.interpolator = android.view.animation.OvershootInterpolator(0.8f)
        anim.addUpdateListener {
            if (gen != viewGen) {
                try {
                    anim.cancel()
                } catch (_: Throwable) {
                }
                return@addUpdateListener
            }
            params.x = it.animatedValue as Int
            try {
                if (view.isAttachedToWindow) wm?.updateViewLayout(view, params)
            } catch (_: Throwable) {
            }
        }
        anim.start()
        bubbleX = target
        bubbleY = params.y
        getSharedPreferences("toolbox", MODE_PRIVATE).edit().putInt("x", target).putInt("y", params.y).apply()
    }

    private fun safeRebuild() = rebuild("safe")

    @Volatile private var touchActive = false
    private var pendingRebuild: String? = null
    private val rebuildTimes = ArrayDeque<Long>()

    /** 防抖 ≥300ms；手指按下期间推迟到 ACTION_UP，避免点击中途换掉视图。 */
    private fun requestRebuild(reason: String) {
        pendingRebuild = reason
        handler.removeCallbacks(flushRebuild)
        handler.postDelayed(flushRebuild, 300L)
    }

    private val flushRebuild = Runnable {
        val r = pendingRebuild ?: return@Runnable
        if (touchActive) {
            ToolboxCrashLog.append(this, "INFO", "rebuild deferred reason=$r touch_active")
            return@Runnable
        }
        pendingRebuild = null
        rebuild(r)
    }

    private fun rebuild(reason: String = "unspecified") {
        val now = SystemClock.elapsedRealtime()
        rebuildTimes.addLast(now)
        while (rebuildTimes.isNotEmpty() && now - rebuildTimes.first() > 1000L) rebuildTimes.removeFirst()
        if (rebuildTimes.size > 5) {
            ToolboxCrashLog.append(this, "WARN", "rebuild storm ${rebuildTimes.size}/s reason=$reason; skipped")
            return
        }
        try {
            val panel = card ?: return
            if (!expanded) return
            panel.removeAllViews()
            when (mode) {
                Mode.TOOLS -> buildTools(panel)
                Mode.VAULT -> buildVault(panel)
                Mode.VAULT_DETAIL -> buildVaultDetail(panel)
            }
            // 全屏窗内只重新摆放面板，不改窗口尺寸
            positionPanel()
        } catch (t: Throwable) {
            Log.w(TAG, "rebuild failed reason=$reason", t)
            ToolboxCrashLog.append(this, "ERROR", "rebuild mode=$mode reason=$reason", t)
        }
    }

    private fun registerVaultListener() {
        // BlackBox 在宿主进程也 hook 了 AMS broadcastIntent，sendBroadcast 会 NPE。
        // 改用进程内监听 + FileObserver，不再 registerReceiver / sendBroadcast。
        if (vaultChangedListener == null) {
            val l: () -> Unit = {
                handler.post {
                    try {
                        VaultStore.invalidateCache()
                    } catch (_: Throwable) {
                    }
                    refreshVaultAsync(thenRebuild = true)
                    ToolboxCrashLog.append(
                        this@ToolboxOverlayService,
                        "INFO",
                        "vault refreshed reason=in_process",
                    )
                }
            }
            vaultChangedListener = l
            try {
                VaultStore.addChangeListener(l)
            } catch (err: Throwable) {
                ToolboxCrashLog.append(this, "ERROR", "addChangeListener", err)
                vaultChangedListener = null
            }
        }
        startVaultFileObserver()
    }

    private fun unregisterVaultListener() {
        val l = vaultChangedListener
        vaultChangedListener = null
        if (l != null) {
            try {
                VaultStore.removeChangeListener(l)
            } catch (_: Throwable) {
            }
        }
        val r = vaultReceiver
        vaultReceiver = null
        if (r != null) {
            try {
                unregisterReceiver(r)
            } catch (_: Throwable) {
            }
        }
        try {
            vaultObserver?.stopWatching()
        } catch (_: Throwable) {
        }
        vaultObserver = null
    }

    private fun startVaultFileObserver() {
        if (vaultObserver != null) return
        val dir = try {
            filesDir?.absolutePath
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            @Suppress("DEPRECATION")
            val obs = object : android.os.FileObserver(
                dir,
                android.os.FileObserver.CLOSE_WRITE or
                    android.os.FileObserver.MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != VaultStore.FILE_JSON) return
                    handler.post {
                        try {
                            VaultStore.invalidateCache()
                        } catch (_: Throwable) {
                        }
                        refreshVaultAsync(thenRebuild = expanded && mode == Mode.VAULT)
                    }
                }
            }
            vaultObserver = obs
            obs.startWatching()
        } catch (err: Throwable) {
            ToolboxCrashLog.append(this, "WARN", "vault FileObserver failed", err)
            vaultObserver = null
        }
    }

    private fun openPasswordBook() {
        try {
            startActivity(MainActivity.vaultLaunchIntent(this, uid.ifBlank { pkg }))
            ToolboxCrashLog.append(this, "INFO", "open password book pkg=$pkg")
        } catch (err: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "open password book failed", err)
            toast("无法打开密码本")
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun header(title: String, action: String, onAction: () -> Unit): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            // 左侧窄把手专用于拖动，避免标题 OnTouch 吞掉子控件事件
            val handle = View(this@ToolboxOverlayService).apply {
                background = round(if (pal.night) 0x55FFFFFF.toInt() else 0x332F7CF6.toInt(), dp(2).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(22)).apply {
                    marginEnd = dp(10)
                }
                contentDescription = "拖动"
                setOnTouchListener(dragListener { })
            }
            addView(handle)
            val name = TextView(this@ToolboxOverlayService).apply {
                text = title
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                isClickable = false
                isFocusable = false
            }
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(link(action, onAction))
        }
    }


    private fun toolboxHeader(): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            val appIcon = ImageView(this@ToolboxOverlayService).apply {
                setImageResource(R.drawable.ic_toolbox)
                val s = dp(28)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(8) }
            }
            addView(appIcon)
            val col = LinearLayout(this@ToolboxOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ToolboxOverlayService).apply {
                    text = "工具箱"
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                val cloneName = resolvedLabel.ifBlank { if (pkg.isBlank()) "等待打开分身" else pkg }
                addView(TextView(this@ToolboxOverlayService).apply {
                    text = cloneName
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                })
            }
            addView(col)
            if (resolvedIcon != null || pkg.isNotBlank()) {
                val cloneIcon = ImageView(this@ToolboxOverlayService).apply {
                    val s = dp(28)
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(8) }
                    val d = resolvedIcon ?: loadIcon(pkg)
                    if (d != null) setImageDrawable(d) else setImageResource(R.drawable.ic_toolbox)
                }
                addView(cloneIcon)
            }
            addView(TextView(this@ToolboxOverlayService).apply {
                text = "收起"
                setTextColor(pal.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8), dp(10), dp(4), dp(10))
                minimumHeight = dp(44)
                setOnClickListener { collapse() }
            })
        }
    }

    private fun buildTools(panel: LinearLayout) {
        panel.addView(toolboxHeader())

        val shown = resolvedLabel.ifBlank { pkg }.ifBlank { "分身" }
        val hint = TextView(this).apply {
            text = if (pkg.isBlank()) "分身运行中" else "当前分身 · $shown"
            setTextColor(Palette(this@ToolboxOverlayService).sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(hint)
        for (tool in ToolboxCatalog.tools()) {
            panel.addView(toolRow(tool.title, tool.summary) {
                if (tool.id == "vault") {
                    vaultDetailId = ""
                    mode = Mode.VAULT
                    try {
                        VaultStore.invalidateCache()
                    } catch (_: Throwable) {
                    }
                    rebuild("enter_vault")
                    refreshVaultAsync()
                } else {
                    toast("这个工具还没接上")
                }
            })
        }
    }

    private fun emptyVault(): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(16), dp(8), dp(8))
            val icon = ImageView(this@ToolboxOverlayService).apply {
                setImageResource(R.drawable.ic_toolbox)
                val s = dp(48)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { bottomMargin = dp(8) }
                imageAlpha = 160
            }
            addView(icon)
            addView(TextView(this@ToolboxOverlayService).apply {
                text = "还没有账号"
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@ToolboxOverlayService).apply {
                text = "请到 Alter Ego App 的「密码本」里添加。工具箱只负责查看、一键填入和复制。"
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(button("去密码本添加") { openPasswordBook() })
        }
    }

    /**
     * 一级：全部账号的备注名列表（可滚动，不按当前分身过滤）。
     * 当前分身的条目排在前面，方便就近选用；点备注进入二级详情。
     */
    private fun buildVault(panel: LinearLayout) {
        panel.addView(header("密码本", "返回") {
            vaultDetailId = ""
            mode = Mode.TOOLS
            rebuild("vault_back")
        })
        panel.addView(button("去密码本添加") { openPasswordBook() })
        if (!OemAdapter.accessibilityOn(this)) {
            panel.addView(TextView(this).apply {
                text = "● 无障碍未开启：点「填入」只能复制，需手动粘贴"
                setTextColor(0xFFE0A030.toInt())
                textSize = 12f
                setPadding(dp(4), dp(4), dp(4), dp(2))
            })
            panel.addView(button("开启无障碍（一键填入）") { openAccessibility() })
        }
        val all = vaultCache.get()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (all.isEmpty()) {
            col.addView(emptyVault())
        } else {
            panel.addView(TextView(this).apply {
                text = "全部 ${all.size} 个账号 · 点备注名查看 / 填入（不限当前分身）"
                setTextColor(Palette(this@ToolboxOverlayService).sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(2), dp(4), dp(6))
            })
            val sorted = all.sortedWith(
                compareBy<VaultStore.Entry> { e -> if (matchesInstance(e)) 0 else 1 }
                    .thenBy { remarkTitle(it).lowercase() },
            )
            val hasCurrent = sorted.any { matchesInstance(it) }
            var sectionPlaced = ""
            for (e in sorted) {
                val want = when {
                    matchesInstance(e) -> "当前分身"
                    hasCurrent -> "其他分身"
                    else -> "全部账号"
                }
                if (sectionPlaced != want) {
                    sectionPlaced = want
                    col.addView(section(want))
                }
                col.addView(remarkRow(e))
            }
        }
        scroll.addView(col)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
    }

    /** 备注名行：主标题 + 所属分身副标题，点进详情。 */
    private fun remarkRow(e: VaultStore.Entry): LinearLayout {
        val pal = Palette(this)
        val title = remarkTitle(e)
        val sub = remarkSubtitle(e)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rowBg(pal)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            minimumHeight = dp(52)
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(8)
            layoutParams = gap
            isClickable = true
            isFocusable = true
            val textCol = LinearLayout(this@ToolboxOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ToolboxOverlayService).apply {
                    text = title
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (sub.isNotBlank()) {
                    addView(TextView(this@ToolboxOverlayService).apply {
                        text = sub
                        setTextColor(pal.sub)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            }
            addView(textCol)
            addView(TextView(this@ToolboxOverlayService).apply {
                text = "›"
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(8), 0, dp(4), 0)
            })
            setOnClickListener {
                tapFeedback(it)
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap vault remark id=${e.id}")
                vaultDetailId = e.id
                mode = Mode.VAULT_DETAIL
                rebuild("vault_detail")
            }
        }
    }

    /** 二级：原有复制账号/密码 + 一键填入。 */
    private fun buildVaultDetail(panel: LinearLayout) {
        val e = vaultCache.get().firstOrNull { it.id == vaultDetailId }
        panel.addView(header(e?.let { remarkTitle(it) } ?: "账号", "返回") {
            vaultDetailId = ""
            mode = Mode.VAULT
            rebuild("vault_detail_back")
        })
        if (e == null) {
            panel.addView(TextView(this).apply {
                text = "这条账号已删除或不存在"
                setTextColor(Palette(this@ToolboxOverlayService).sub)
                setPadding(dp(8), dp(16), dp(8), dp(8))
            })
            panel.addView(button("回到列表") {
                vaultDetailId = ""
                mode = Mode.VAULT
                rebuild("vault_missing_back")
            })
            return
        }
        if (!OemAdapter.accessibilityOn(this)) {
            panel.addView(TextView(this).apply {
                text = "● 无障碍未开启：点「填入」只能复制，需手动粘贴"
                setTextColor(0xFFE0A030.toInt())
                textSize = 12f
                setPadding(dp(4), dp(4), dp(4), dp(2))
            })
        }
        val sub = remarkSubtitle(e)
        if (sub.isNotBlank()) {
            panel.addView(TextView(this).apply {
                text = sub
                setTextColor(Palette(this@ToolboxOverlayService).sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), 0, dp(4), dp(8))
            })
        }
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(entryRow(e))
        scroll.addView(col)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
    }

    /** 列表主标题：备注名 → 账号 → 分身名 / 包名。 */
    private fun remarkTitle(e: VaultStore.Entry): String {
        val label = e.label.trim()
        if (label.isNotEmpty() && label != "未命名") return label
        if (e.username.isNotBlank()) return e.username.trim()
        return cloneDisplayName(e).ifBlank { e.pkg.ifBlank { "未命名" } }
    }

    /** 副标题：所属分身（自定义名优先）· 包名精简。 */
    private fun remarkSubtitle(e: VaultStore.Entry): String {
        val clone = cloneDisplayName(e)
        val cur = if (matchesInstance(e)) " · 当前分身" else ""
        return when {
            clone.isNotBlank() -> "$clone$cur"
            e.pkg.isNotBlank() -> "${e.pkg}$cur"
            else -> cur.trimStart(' ', '·')
        }
    }

    private fun cloneDisplayName(e: VaultStore.Entry): String {
        val userId = CloneId.userId(e.cloneUid.ifBlank { null })
        val fromNames = readCloneLabel(e.pkg, userId)
        if (fromNames.isNotBlank()) return fromNames
        val app = appLabelCached(e.pkg)
        return when {
            app.isNotBlank() && userId > 0 -> "$app 分身${userId + 1}"
            app.isNotBlank() -> app
            userId > 0 -> "${e.pkg} 分身${userId + 1}"
            else -> e.pkg
        }
    }

    private fun readCloneLabel(pkg: String, userId: Int): String {
        if (pkg.isBlank()) return ""
        return try {
            val f = File(filesDir, CloneNames.FILE)
            if (!f.isFile) return ""
            val json = org.json.JSONObject(f.readText())
            json.optString(CloneNames.key(pkg, userId), "").trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun entryRow(e: VaultStore.Entry): LinearLayout {
        val pal = Palette(this)
        var showPass = false
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rowBg(pal)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            clipChildren = false
            clipToPadding = false
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(8)
            layoutParams = gap

            addView(TextView(this@ToolboxOverlayService).apply {
                text = e.label.ifBlank { "未命名" }
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })

            // 账号行：左侧文案，右侧复制
            addView(fieldRow(
                label = "账号",
                value = e.username.ifBlank { "未填写" },
                valueColor = pal.sub,
                trailing = listOf(
                    iconAction(R.drawable.ic_copy, "复制账号") {
                        tapFeedback(it)
                        copyUser(e)
                    },
                ),
            ))

            val passTv = TextView(this@ToolboxOverlayService).apply {
                text = maskPass(e.password, show = false)
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val eyeBtn = iconAction(R.drawable.ic_visibility, "显示或隐藏密码") { v ->
                tapFeedback(v)
                showPass = !showPass
                passTv.text = maskPass(e.password, show = showPass)
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap toggle_pass show=$showPass")
            }
            addView(fieldRow(
                label = "密码",
                valueView = passTv,
                trailing = listOf(
                    eyeBtn,
                    iconAction(R.drawable.ic_copy, "复制密码") {
                        tapFeedback(it)
                        copyPass(e)
                    },
                ),
            ))

            addView(primaryFillButton("一键填入") {
                tapFeedback(it)
                performFill(e)
            })
        }
    }

    private fun maskPass(password: String, show: Boolean): String = when {
        password.isEmpty() -> "未填写"
        show -> password
        else -> "•".repeat(password.length.coerceIn(6, 12))
    }

    private fun fieldRow(
        label: String,
        value: String? = null,
        valueView: TextView? = null,
        valueColor: Int = Palette(this).sub,
        trailing: List<View>,
    ): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(this@ToolboxOverlayService).apply {
                text = label
                setTextColor(pal.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val vv = valueView ?: TextView(this@ToolboxOverlayService).apply {
                text = value.orEmpty()
                setTextColor(valueColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (vv.layoutParams == null) {
                vv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(vv)
            for (t in trailing) addView(t)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun iconAction(drawable: Int, desc: String, onClick: (View) -> Unit): ImageView {
        val pal = Palette(this)
        val size = dp(44)
        return ImageView(this).apply {
            setImageResource(drawable)
            contentDescription = desc
            isClickable = true
            isFocusable = true
            imageTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            background = circle(if (pal.night) 0x22FFFFFF.toInt() else 0x142F7CF6.toInt())
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(2) }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "touch_down $desc")
                }
                false
            }
            setOnClickListener { v ->
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap click $desc")
                onClick(v)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun primaryFillButton(text: String, onClick: (View) -> Unit): TextView {
        val pal = Palette(this)
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = round(pal.accent, dp(18).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "touch_down fill")
                }
                false
            }
            setOnClickListener { v ->
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap click fill")
                onClick(v)
            }
        }
    }

    private fun tapFeedback(v: View) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) {
        }
    }

    @Volatile private var lastPerformFillAt = 0L

    /**
     * 一键填入：写入**当前前台窗口**的输入框，不要求条目所属包名与当前分身一致。
     * 这样从「其他分身」的账号列表里选一条，也能填到正在登录的页面。
     */
    private fun performFill(e: VaultStore.Entry) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerformFillAt < 1000L) {
            ToolboxCrashLog.append(this, "INFO", "tap fill debounce id=${e.id} gap=${now - lastPerformFillAt}ms")
            toast("正在填入，请稍候")
            return
        }
        lastPerformFillAt = now
        ToolboxCrashLog.append(this, "INFO", "tap fill id=${e.id} userLen=${e.username.length} passLen=${e.password.length}")
        // 先收起面板，避免无障碍读到悬浮窗；再填入。
        if (expanded) collapse()
        handler.postDelayed({
            runBg {
                val r = try {
                    ToolboxController.fill(this@ToolboxOverlayService, e.username, e.password)
                } catch (t: Throwable) {
                    ToolboxCrashLog.append(this@ToolboxOverlayService, "ERROR", "tap fill crashed", t)
                    "error"
                }
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap fill result=$r")
                handler.post {
                    toast(fillMessage(r))
                    if (r == "copied" && !OemAdapter.accessibilityOn(this@ToolboxOverlayService)) {
                        handler.postDelayed({ toast("想自动填入？展开工具箱 → 密码本 → 「开启无障碍」") }, 2200)
                    }
                }
            }
        }, 360)
    }

    private fun copyUser(e: VaultStore.Entry) {
        ToolboxCrashLog.append(this, "INFO", "tap copy_account id=${e.id} empty=${e.username.isEmpty()}")
        val ok = ToolboxController.copyText(this, e.username)
        ToolboxCrashLog.append(this, "INFO", "tap copy_account ok=$ok")
        toast(if (ok) "已复制账号" else if (e.username.isEmpty()) "没有账号可复制" else "复制失败")
    }

    private fun copyPass(e: VaultStore.Entry) {
        ToolboxCrashLog.append(this, "INFO", "tap copy_password id=${e.id} empty=${e.password.isEmpty()}")
        val ok = ToolboxController.copyText(this, e.password)
        ToolboxCrashLog.append(this, "INFO", "tap copy_password ok=$ok")
        toast(if (ok) "已复制密码" else if (e.password.isEmpty()) "没有密码可复制" else "复制失败")
    }

    private fun matchesInstance(e: VaultStore.Entry): Boolean {
        val cu = e.cloneUid
        if (cu.isNotBlank()) return cu == uid || (cu == pkg && CloneId.userId(uid) == 0)
        return e.pkg == pkg && CloneId.userId(uid) == 0
    }

    private fun openAccessibility() {
        ToolboxCrashLog.append(this, "INFO", "tap open_accessibility")
        if (expanded) collapse()
        val r = try { A11yJump.open(this) } catch (t: Throwable) { "error" }
        toast(when (r) {
            "on" -> "无障碍已开启"
            "restricted" -> "请先点右上角「⋮ → 允许受限制的设置」，再回来开启无障碍"
            "details" -> "请打开「Alter Ego」无障碍开关"
            "list" -> "请在列表中找到「Alter Ego」并开启"
            else -> "打开失败，请到 设置 → 无障碍 中开启"
        })
    }

    private fun fillMessage(r: String) = when (r) {
        "filled" -> "已填入"
        "copied" -> "无障碍未开启，已复制，请粘贴"
        "copied_no_field" -> "未找到输入框，已复制"
        "copied_user" -> "已复制账号"
        "copied_pass" -> "已复制密码"
        else -> "填入失败"
    }

    private fun runBg(block: () -> Unit) {
        try {
            bg.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    ToolboxCrashLog.append(this, "ERROR", "bg task", t)
                }
            }
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "bg submit", t)
            // 执行器挂了就直接在当前线程兜底（仍包住）
            try {
                block()
            } catch (t2: Throwable) {
                ToolboxCrashLog.append(this, "ERROR", "bg fallback", t2)
            }
        }
    }

    private fun refreshVaultAsync(thenRebuild: Boolean = false) {
        runBg {
            val list = try {
                VaultStore.list(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val old = vaultCache.getAndSet(list)
            if (old == list) return@runBg
            if (thenRebuild || (expanded && (mode == Mode.VAULT || mode == Mode.VAULT_DETAIL))) {
                handler.post {
                    if (!expanded) return@post
                    requestRebuild(if (thenRebuild) "vault_refresh" else "vault_async")
                }
            }
        }
    }

    private fun resolveAppInfoAsync(packageName: String) {
        if (packageName.isBlank()) return
        runBg {
            val label = appLabelSync(packageName)
            val icon = loadIcon(packageName)
            handler.post {
                if (pkg == packageName) {
                    resolvedLabel = label
                    resolvedIcon = icon
                    if (expanded) {
                        requestRebuild("app_info")
                    }
                    try {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, notification())
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private val labelMemo = HashMap<String, String>()

    private fun appLabelCached(packageName: String): String {
        if (packageName.isBlank()) return ""
        labelMemo[packageName]?.let { return it }
        // 轻量：若已在后台解析过当前包，直接用；否则先返回包名，异步补全
        if (packageName == pkg && resolvedLabel.isNotBlank()) return resolvedLabel
        runBg {
            val l = appLabelSync(packageName)
            handler.post {
                labelMemo[packageName] = l
                if (expanded && (mode == Mode.VAULT || mode == Mode.VAULT_DETAIL)) requestRebuild("label_memo")
            }
        }
        return packageName
    }

    private fun appLabelSync(packageName: String): String {
        if (packageName.isBlank()) return ""
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Throwable) {
            packageName
        }
    }

    private fun loadIcon(packageName: String): Drawable? {
        if (packageName.isBlank()) return null
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Throwable) {
            null
        }
    }

    private fun toolRow(title: String, summary: String, onClick: () -> Unit): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rowBg(pal)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val t = TextView(this@ToolboxOverlayService).apply {
                text = title
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val s = TextView(this@ToolboxOverlayService).apply {
                text = summary
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            addView(t)
            addView(s)
            setOnClickListener { onClick() }
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(8)
            layoutParams = gap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun button(text: String, onClick: () -> Unit): TextView {
        val pal = Palette(this)
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = round(pal.accent, dp(20).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(44)
            isClickable = true
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "touch_down btn=$text")
                }
                false
            }
            setOnClickListener {
                ToolboxCrashLog.append(this@ToolboxOverlayService, "INFO", "tap click btn=$text")
                onClick()
            }
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(8)
            layoutParams = gap
        }
    }

    private fun link(text: String, onClick: () -> Unit): TextView {
        val pal = Palette(this)
        return TextView(this).apply {
            this.text = text
            setTextColor(pal.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Palette(this@ToolboxOverlayService).sub)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Palette(this@ToolboxOverlayService).accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(8), 0, dp(2))
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(onClick: () -> Unit): View.OnTouchListener {
        val slop = dp(6)
        return object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val params = lp ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelIdleDim()
                        downX = e.rawX
                        downY = e.rawY
                        startX = if (expanded) bubbleX else params.x
                        startY = if (expanded) bubbleY else params.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragging = true
                        if (dragging) {
                            if (expanded) {
                                // 全屏窗内：拖面板（改 margin），窗口本身不动
                                val (nx, ny) = clampOverlayPosition(
                                    startX + dx, startY + dy,
                                    card?.width?.coerceAtLeast(dp(48)) ?: dp(320),
                                    card?.height?.coerceAtLeast(dp(48)) ?: dp(200),
                                    screenWidth(), screenHeight(),
                                )
                                bubbleX = nx
                                bubbleY = ny
                                (card?.layoutParams as? FrameLayout.LayoutParams)?.let { lpp ->
                                    lpp.leftMargin = nx
                                    lpp.topMargin = ny
                                    card?.layoutParams = lpp
                                }
                            } else {
                                val (nx, ny) = clampOverlayPosition(
                                    startX + dx, startY + dy, dp(48), dp(48),
                                    screenWidth(), screenHeight(),
                                )
                                params.x = nx
                                params.y = ny
                                try {
                                    wm?.updateViewLayout(root, params)
                                } catch (_: Throwable) {
                                }
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) onClick()
                        else {
                            if (expanded) {
                                bubbleX = params.x
                                bubbleY = params.y
                                getSharedPreferences("toolbox", MODE_PRIVATE)
                                    .edit().putInt("x", params.x).putInt("y", params.y).apply()
                            } else {
                                getSharedPreferences("toolbox", MODE_PRIVATE)
                                    .edit().putInt("x", params.x).putInt("y", params.y).apply()
                                bubbleX = params.x
                                bubbleY = params.y
                                snapToEdge(halfHide = false)
                                scheduleIdleDim()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun toast(text: String) {
        val show = {
            try {
                Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                ToolboxCrashLog.append(this, "WARN", "toast failed msg=$text", t)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) show()
        else handler.post { show() }
    }

    private fun logWatch(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWatchLogAt < 2_000L) return
        lastWatchLogAt = now
        Log.i(TAG, msg)
        ToolboxCrashLog.append(this, "WATCH", msg)
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "工具箱", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this,
            7,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val follow = when {
            resolvedLabel.isNotBlank() -> "正在跟随 $resolvedLabel"
            pkg.isNotBlank() -> "正在跟随 $pkg"
            else -> "分身运行时可以打开密码本"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle("工具箱悬浮窗")
            .setContentText(follow)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun bubbleDrawable(pal: Palette, idle: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        // 半透明强调色，带浅描边，呈现毛玻璃感（不做 FLAG_BLUR_BEHIND）
        val base = pal.accent
        val alpha = if (idle) 0x88 else 0xCC
        setColor((base and 0x00FFFFFF.toInt()) or (alpha shl 24))
        setStroke(dp(1).coerceAtLeast(1), if (pal.night) 0x55FFFFFF.toInt() else 0x88FFFFFF.toInt())
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply {
        cornerRadius = r
        setColor(color)
    }

    private fun panelBg(pal: Palette) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(pal.bg)
        setStroke(dp(1).coerceAtLeast(1), pal.stroke)
    }

    private fun rowBg(pal: Palette) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (pal.night) 0x18FFFFFF.toInt() else 0x10000000.toInt())
    }

    private enum class Mode { TOOLS, VAULT, VAULT_DETAIL }

    private class Palette(ctx: Context) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val accent = if (Build.VERSION.SDK_INT >= 31) {
            try {
                ctx.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            } catch (_: Throwable) {
                0xFF2F7CF6.toInt()
            }
        } else {
            0xFF2F7CF6.toInt()
        }
        // 半透明着色背景，不做系统 blurBehind
        val bg = if (night) 0xE61B1F2A.toInt() else 0xF2FFFFFF.toInt()
        val text = if (night) 0xFFF4F6FB.toInt() else 0xFF1B1F2A.toInt()
        val sub = if (night) 0xFFB7BECC.toInt() else 0xFF5C6575.toInt()
        val stroke = if (night) 0x40FFFFFF.toInt() else 0x55FFFFFF.toInt()
    }

    companion object {
        private const val TAG = "ToolboxOverlay"

        /** 纯函数：把悬浮窗左上角夹到屏幕内，保证宽高完整可见。供测试与运行时共用。 */
        fun clampOverlayPosition(
            x: Int,
            y: Int,
            winW: Int,
            winH: Int,
            screenW: Int,
            screenH: Int,
        ): Pair<Int, Int> {
            val maxX = (screenW - winW).coerceAtLeast(0)
            val maxY = (screenH - winH).coerceAtLeast(0)
            return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
        }
        private const val CHANNEL = "toolbox_overlay"
        private const val NOTIFICATION_ID = 1004
        private const val GRACE_MS = 30_000L
        /** HyperOS 上进程列表常空；启动后这段时间内即使看不到 :pN 也继续显示气泡。 */
        private const val STICKY_MS = 15 * 60_000L
        private const val HIDE_AFTER_MS = 20 * 60_000L
        private const val STOP_AFTER_MS = 30 * 60_000L
        private const val TICK_MS = 1_500L
        /** 连续否定次数与最短窗口：避免 HyperOS IME 时 Settings.canDrawOverlays 瞬假。 */
        private const val DENY_STREAK_MIN = 3
        private const val DENY_WINDOW_MS = 10_000L
        /** 用户在设置里关闭工具箱时显式停服，不靠 tick 轮询。 */
        const val ACTION_USER_STOP = "com.parallelspace.parallel_space.action.TOOLBOX_USER_STOP"
    }
}
