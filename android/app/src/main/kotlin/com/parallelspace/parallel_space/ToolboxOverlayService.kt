package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 分身在前台时显示的可拖动、可收起悬浮窗。
 *
 * 1.3.7 起：
 * - 编辑时不因 IME / 窗外触摸误收起；仅在无焦点字段时点窗外才收起，或点「收起」/气泡。
 * - 抢焦点编辑时，分身进程重要性会短暂下降；不再因此 removeView / stopSelf。
 * - START_STICKY + 看门狗：工具箱开启且分身仍在时，气泡被系统摘掉会自动加回。
 * - 添加账号的包名字段改为只读：图标 + 应用名（自动取当前分身）。
 *
 * 1.3.9：
 * - HyperOS 上 getRunningAppProcesses 经常看不到 :pN，改为 /proc cmdline 兜底，
 *   并在启动后 STICKY_MS 内即使列表空也保持气泡，避免「分身还在、小球消失」。
 * - 所有悬浮窗操作与 rebuild 包 try/catch；动画代数防泄漏；崩溃写入 toolbox_crash.log。
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
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var mode = Mode.TOOLS
    private var pkg = ""
    private var launchedAt = 0L
    private var lastSeenClientAt = 0L
    private var editingId: String? = null
    private var labelInput: EditText? = null
    private var userInput: EditText? = null
    private var passInput: EditText? = null
    private var resolvedLabel: String = ""
    private var resolvedIcon: Drawable? = null
    private val vaultCache = AtomicReference<List<VaultStore.Entry>>(emptyList())
    private var lastWatchLogAt = 0L
    /** 视图代数：removeView / 重建时递增，避免旧动画回调整坏新窗。 */
    private var viewGen = 0
    private var edgeAnimator: android.animation.ValueAnimator? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = intent?.getStringExtra("pkg")?.takeIf { it.isNotBlank() }
        if (incoming != null) {
            pkg = incoming
            launchedAt = SystemClock.elapsedRealtime()
            lastSeenClientAt = launchedAt
            resolveAppInfoAsync(pkg)
            ToolboxCrashLog.append(this, "INFO", "start pkg=$pkg")
        } else if (pkg.isBlank()) {
            pkg = ToolboxController.lastPackage(this)
            if (pkg.isNotBlank()) resolveAppInfoAsync(pkg)
            ToolboxCrashLog.append(this, "INFO", "restart sticky pkg=$pkg flags=$flags")
        }
        if (launchedAt == 0L) launchedAt = SystemClock.elapsedRealtime()
        if (!ToolboxController.enabled(this) || !OemAdapter.canDrawOverlay(this)) {
            ToolboxCrashLog.append(this, "WARN", "stop: enabled=${ToolboxController.enabled(this)} overlay=${OemAdapter.canDrawOverlay(this)}")
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
        ToolboxController.onVaultAuthedListener = { handler.post { safeRebuild() } }
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
        if (ToolboxController.onVaultAuthedListener != null) ToolboxController.onVaultAuthedListener = null
        removeView()
        // 不 shutdownNow：sticky 重启可能复用同一进程内的排队任务；让空闲守护线程自然回收
        super.onDestroy()
    }

    private fun tickOnce() {
        if (!ToolboxController.enabled(this) || !OemAdapter.canDrawOverlay(this)) {
            ToolboxCrashLog.append(this, "WARN", "tick stop: enabled/overlay lost")
            stopSelf()
            return
        }
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

        // 正在编辑或面板展开时绝不因短暂不可见而拆窗
        if (expanded || mode == Mode.EDIT || hasFieldFocus()) {
            ensureView(force = root == null || root?.isAttachedToWindow != true)
            return
        }

        val quietFor = now - lastSeenClientAt.coerceAtLeast(launchedAt)
        if (quietFor > STOP_AFTER_MS && !client) {
            logWatch("no clone for ${quietFor}ms; stopSelf")
            ToolboxCrashLog.append(this, "INFO", "stopSelf quiet=${quietFor}ms")
            stopSelf()
            return
        }
        // 分身短暂切后台：只收起面板，保留气泡；超过 HIDE_AFTER 再摘气泡，服务仍活着方便回来
        // HyperOS 上进程列表经常空，quiet 计时用 STICKY 护着，不会刚开分身就被摘掉
        if (quietFor > HIDE_AFTER_MS) {
            if (root != null) {
                logWatch("clone quiet ${quietFor}ms; hide bubble (service stays)")
                ToolboxCrashLog.append(this, "INFO", "hide bubble quiet=${quietFor}ms")
                removeView()
            }
        }
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
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
        }
        card = panel
        val holder = FrameLayout(this)
        holder.addView(bubbleView, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        holder.addView(
            panel,
            FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        root = holder
        val prefs = getSharedPreferences("toolbox", Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", screenWidth() - bubbleSize - dp(12))
            y = prefs.getInt("y", dp(160))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        // 不要 FLAG_BLUR_BEHIND / dimAmount：HyperOS 上会把整屏糊掉，分身无法点。
        lp = params
        bubbleView.setOnTouchListener(dragListener { toggle() })
        holder.setOnTouchListener { _, e ->
            if (expanded && e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // 编辑中 / 输入框有焦点时：IME 弹出也会发 OUTSIDE，不能收起
                if (mode == Mode.EDIT || hasFieldFocus()) {
                    return@setOnTouchListener true
                }
                collapse()
                true
            } else false
        }
        try {
            window.addView(holder, params)
            applyVisibility()
            scheduleIdleDim()
            ToolboxCrashLog.append(this, "INFO", "addView ok x=${params.x} y=${params.y} pkg=$pkg")
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
            ToolboxCrashLog.append(this, "ERROR", "addView failed", t)
            root = null
            card = null
            bubble = null
            // 下一 tick 看门狗会再试
        }
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
        labelInput = null
        userInput = null
        passInput = null
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
            expanded = true
            cancelIdleDim()
            bubble?.animate()?.cancel()
            bubble?.alpha = 1f
            focusable(false)
            applyVisibility()
            val panel = card
            val gen = viewGen
            if (panel != null) {
                panel.animate().cancel()
                panel.alpha = 0f
                panel.scaleX = 0.92f
                panel.scaleY = 0.92f
                panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .withEndAction { if (gen != viewGen) return@withEndAction }
                    .start()
            }
            refreshVaultAsync()
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "expand failed", t)
        }
    }

    private fun collapse() {
        try {
            hideIme()
            expanded = false
            mode = Mode.TOOLS
            editingId = null
            focusable(false)
            val panel = card
            val gen = viewGen
            if (panel != null && panel.visibility == View.VISIBLE) {
                panel.animate().cancel()
                panel.animate()
                    .alpha(0f)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(140)
                    .withEndAction {
                        if (gen != viewGen) return@withEndAction
                        applyVisibility()
                        scheduleIdleDim()
                    }
                    .start()
            } else {
                applyVisibility()
                scheduleIdleDim()
            }
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "collapse failed", t)
            try {
                applyVisibility()
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyVisibility() {
        try {
            bubble?.visibility = if (expanded) View.GONE else View.VISIBLE
            card?.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) safeRebuild()
            val params = lp ?: return
            val view = root ?: return
            try {
                if (view.isAttachedToWindow) wm?.updateViewLayout(view, params)
            } catch (t: Throwable) {
                Log.w(TAG, "updateViewLayout in applyVisibility failed", t)
                ToolboxCrashLog.append(this, "ERROR", "updateViewLayout applyVisibility", t)
                // 清掉引用，让看门狗重建；不要留下半残视图
                removeView()
            }
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "applyVisibility failed", t)
        }
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
        getSharedPreferences("toolbox", MODE_PRIVATE).edit().putInt("x", target).putInt("y", params.y).apply()
    }

    private fun focusable(on: Boolean) {
        val params = lp ?: return
        val view = root ?: return
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // on=true：编辑要键盘；仍 NOT_TOUCH_MODAL，并 WATCH_OUTSIDE（由 handler 决定是否收起）
        // on=false：气泡或只读面板，NOT_FOCUSABLE
        params.flags = if (on) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else if (expanded) {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        params.dimAmount = 0f
        try {
            if (view.isAttachedToWindow) wm?.updateViewLayout(view, params)
        } catch (t: Throwable) {
            Log.w(TAG, "updateViewLayout in focusable failed", t)
            ToolboxCrashLog.append(this, "ERROR", "updateViewLayout focusable", t)
            removeView()
        }
    }

    private fun hasFieldFocus(): Boolean {
        val a = labelInput?.hasFocus() == true
        val b = userInput?.hasFocus() == true
        val c = passInput?.hasFocus() == true
        return a || b || c
    }

    private fun hideIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = root?.windowToken ?: return
            imm.hideSoftInputFromWindow(token, 0)
        } catch (_: Throwable) {
        }
    }

    private fun safeRebuild() = rebuild()

    private fun rebuild() {
        try {
            val panel = card ?: return
            if (!expanded) return
            panel.removeAllViews()
            when (mode) {
                Mode.TOOLS -> buildTools(panel)
                Mode.VAULT -> buildVault(panel)
                Mode.EDIT -> buildEdit(panel)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "rebuild failed", t)
            ToolboxCrashLog.append(this, "ERROR", "rebuild mode=$mode", t)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun header(title: String, action: String, onAction: () -> Unit): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val name = TextView(this@ToolboxOverlayService).apply {
                text = title
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                setOnTouchListener(dragListener { })
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
        focusable(false)
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
                    mode = Mode.VAULT
                    refreshVaultAsync(thenRebuild = true)
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
                text = "添加后可一键填入，或分别复制账号和密码"
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            })
        }
    }

    private fun buildVault(panel: LinearLayout) {
        focusable(false)
        panel.addView(header("账号密码本", "返回") {
            mode = Mode.TOOLS
            rebuild()
        })
        val entries = vaultCache.get()
        panel.addView(button("添加账号") {
            editingId = null
            mode = Mode.EDIT
            rebuild()
        })
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val groups = entries.groupBy { it.groupKey }
        val keys = groups.keys.sortedWith(compareBy<String> { key ->
            val hit = groups[key].orEmpty().any { it.pkg == pkg || it.cloneUid == "w:$pkg" }
            if (hit) 0 else 1
        }.thenBy { it })
        if (keys.isEmpty()) {
            col.addView(emptyVault())
        }
        for (key in keys) {
            val list = groups[key].orEmpty().sortedBy { it.label }
            val current = list.any { it.pkg == pkg }
            val title = if (current) {
                "当前分身 · ${resolvedLabel.ifBlank { list.first().pkg }}"
            } else {
                appLabelCached(list.first().pkg).ifBlank { list.first().pkg }
            }
            col.addView(section(title))
            for (e in list) col.addView(entryRow(e))
        }
        scroll.addView(col)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)))
    }

    private fun entryRow(e: VaultStore.Entry): LinearLayout {
        val pal = Palette(this)
        var showPass = false
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rowBg(pal)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(44)
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(8)
            layoutParams = gap
            val title = TextView(this@ToolboxOverlayService).apply {
                text = e.label.ifBlank { "未命名" }
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@ToolboxOverlayService).apply {
                text = e.username.ifBlank { "未填写用户名" }
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            val passRow = LinearLayout(this@ToolboxOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val passTv = TextView(this@ToolboxOverlayService).apply {
                text = if (e.password.isEmpty()) "未填写密码" else "•".repeat(e.password.length.coerceIn(6, 12))
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val eye = TextView(this@ToolboxOverlayService).apply {
                text = "显示"
                setTextColor(pal.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minimumHeight = dp(44)
                setOnClickListener {
                    showPass = !showPass
                    text = if (showPass) "隐藏" else "显示"
                    passTv.text = when {
                        e.password.isEmpty() -> "未填写密码"
                        showPass -> e.password
                        else -> "•".repeat(e.password.length.coerceIn(6, 12))
                    }
                }
            }
            passRow.addView(passTv)
            passRow.addView(eye)
            addView(title)
            addView(sub)
            addView(passRow)
            val actions = LinearLayout(this@ToolboxOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            actions.addView(primaryChip("一键填入") { performFill(e) })
            actions.addView(outlineChip("复制账号") { copyUser(e) })
            actions.addView(outlineChip("复制密码") { copyPass(e) })
            addView(actions)
            val more = LinearLayout(this@ToolboxOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            more.addView(link("编辑") {
                editingId = e.id
                mode = Mode.EDIT
                rebuild()
            })
            more.addView(link("删除") { confirmDelete(e) })
            addView(more)
            setOnLongClickListener {
                copyPass(e)
                true
            }
        }
    }

    private fun primaryChip(text: String, onClick: () -> Unit): TextView {
        val pal = Palette(this)
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = round(pal.accent, dp(16).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(44)
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
    }

    private fun outlineChip(text: String, onClick: () -> Unit): TextView {
        val pal = Palette(this)
        return TextView(this).apply {
            this.text = text
            setTextColor(pal.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (pal.night) 0x22FFFFFF.toInt() else 0x142F7CF6.toInt())
                setStroke(dp(1).coerceAtLeast(1), (pal.accent and 0x00FFFFFF.toInt()) or 0x66000000.toInt())
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(44)
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }
    }

    private fun confirmDelete(e: VaultStore.Entry) {
        val panel = card ?: return
        panel.addView(body("删除「${e.label}」？"))
        panel.addView(button("确认删除") {
            runBg {
                try {
                    VaultStore.delete(this, e.id)
                } catch (_: Throwable) {
                    handler.post { toast("删除失败") }
                    return@runBg
                }
                refreshVaultAsync(thenRebuild = true)
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun buildEdit(panel: LinearLayout) {
        focusable(true)
        val existing = editingId?.let { id ->
            vaultCache.get().firstOrNull { it.id == id }
        }
        panel.addView(header(if (existing == null) "添加账号" else "编辑账号", "取消") {
            hideIme()
            mode = Mode.VAULT
            focusable(false)
            rebuild()
        })
        panel.addView(body("名称、用户名和密码会保存在本机，方便一键填入。"))
        labelInput = field("名称，例如主号", existing?.label ?: "")
        userInput = field("用户名 / 手机 / 学号", existing?.username ?: "")
        passInput = field("密码", existing?.password ?: "", password = true)
        panel.addView(labelInput)
        panel.addView(userInput)
        panel.addView(passInput)
        // 包名不再手填：只读展示当前分身（图标 + 应用名）
        val bindPkg = (existing?.pkg?.takeIf { it.isNotBlank() } ?: pkg)
        panel.addView(appChip(bindPkg))
        panel.addView(button("保存") { saveEdit(existing, bindPkg) })
    }

    private fun appChip(bindPkg: String): LinearLayout {
        val pal = Palette(this)
        val label = if (bindPkg == pkg && resolvedLabel.isNotBlank()) {
            resolvedLabel
        } else {
            appLabelCached(bindPkg).ifBlank { bindPkg.ifBlank { "未知应用" } }
        }
        val icon = if (bindPkg == pkg) resolvedIcon else loadIcon(bindPkg)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(if (pal.night) 0x22FFFFFF.toInt() else 0x11000000.toInt(), dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(6)
            layoutParams = gap
            val iv = ImageView(this@ToolboxOverlayService).apply {
                val s = dp(28)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(10) }
                if (icon != null) setImageDrawable(icon)
                else setImageResource(R.drawable.ic_toolbox)
                contentDescription = "应用图标"
            }
            addView(iv)
            val col = LinearLayout(this@ToolboxOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                val name = TextView(this@ToolboxOverlayService).apply {
                    text = label
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                }
                val sub = TextView(this@ToolboxOverlayService).apply {
                    text = "自动绑定当前分身"
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                addView(name)
                addView(sub)
            }
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun saveEdit(existing: VaultStore.Entry?, bindPkg: String) {
        val map = mapOf(
            "id" to (existing?.id ?: ""),
            "label" to (labelInput?.text?.toString() ?: ""),
            "user" to (userInput?.text?.toString() ?: ""),
            "pass" to (passInput?.text?.toString() ?: ""),
            "pkg" to bindPkg,
            "clone" to (existing?.cloneUid ?: if (bindPkg.isBlank()) "" else "w:$bindPkg"),
        )
        runBg {
            val r = try {
                VaultStore.upsert(this, map)
            } catch (_: VaultStore.Locked) {
                "locked"
            } catch (_: Throwable) {
                "error"
            }
            handler.post {
                when (r) {
                    "ok" -> {
                        hideIme()
                        mode = Mode.VAULT
                        focusable(false)
                        refreshVaultAsync(thenRebuild = true)
                    }
                    else -> toast(if (r == "error") "保存失败" else r)
                }
            }
        }
    }

    private fun performFill(e: VaultStore.Entry) {
        // 收起面板并把焦点交还给分身窗口，再填入。
        focusable(false)
        if (expanded) collapse()
        handler.postDelayed({
            runBg {
                val r = try {
                    ToolboxController.fill(this, e.username, e.password)
                } catch (_: Throwable) {
                    "error"
                }
                handler.post { toast(fillMessage(r)) }
            }
        }, 320)
    }

    private fun copyUser(e: VaultStore.Entry) {
        val ok = ToolboxController.copyText(this, e.username)
        toast(if (ok) "已复制账号" else if (e.username.isEmpty()) "没有账号可复制" else "复制失败")
    }

    private fun copyPass(e: VaultStore.Entry) {
        val ok = ToolboxController.copyText(this, e.password)
        toast(if (ok) "已复制密码，长按输入框粘贴" else if (e.password.isEmpty()) "没有密码可复制" else "复制失败")
    }

    private fun fillMessage(r: String) = when (r) {
        "filled" -> "已填入当前输入框"
        "copied" -> "未开启无障碍，已复制密码，长按输入框粘贴"
        "copied_no_field" -> "没有找到输入框，已复制密码，长按输入框粘贴"
        "copied_user" -> "已复制账号"
        "copied_pass" -> "已复制密码，长按输入框粘贴"
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
            vaultCache.set(list)
            if (thenRebuild || (expanded && mode == Mode.VAULT)) {
                handler.post { if (expanded) rebuild() }
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
                    if (expanded) rebuild()
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
                if (expanded && mode == Mode.VAULT) rebuild()
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

    private fun button(text: String, onClick: () -> Unit): TextView {
        val pal = Palette(this)
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = round(pal.accent, dp(20).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
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

    private fun field(hint: String, value: String, password: Boolean = false): EditText {
        val pal = Palette(this)
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (pal.night) 0x14FFFFFF.toInt() else 0x08000000.toInt())
                setStroke(dp(1).coerceAtLeast(1), if (pal.night) 0x44FFFFFF.toInt() else 0x332F7CF6.toInt())
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(44)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            setOnFocusChangeListener { _, has ->
                if (has) focusable(true)
            }
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(8)
            layoutParams = gap
        }
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
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragging = true
                        if (dragging) {
                            params.x = (startX + dx).coerceIn(0, (screenWidth() - dp(48)).coerceAtLeast(0))
                            params.y = (startY + dy).coerceIn(0, (screenHeight() - dp(48)).coerceAtLeast(0))
                            try {
                                wm?.updateViewLayout(root, params)
                            } catch (_: Throwable) {
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) onClick()
                        else {
                            getSharedPreferences("toolbox", MODE_PRIVATE).edit().putInt("x", params.x).putInt("y", params.y).apply()
                            snapToEdge(halfHide = false)
                            scheduleIdleDim()
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
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

    private enum class Mode { TOOLS, VAULT, EDIT }

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
        private const val CHANNEL = "toolbox_overlay"
        private const val NOTIFICATION_ID = 1004
        private const val GRACE_MS = 30_000L
        /** HyperOS 上进程列表常空；启动后这段时间内即使看不到 :pN 也继续显示气泡。 */
        private const val STICKY_MS = 15 * 60_000L
        private const val HIDE_AFTER_MS = 20 * 60_000L
        private const val STOP_AFTER_MS = 30 * 60_000L
        private const val TICK_MS = 1_500L
    }
}
