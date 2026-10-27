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
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo

/**
 * 分身在前台时显示的可拖动、可收起悬浮窗。风格对齐主应用的毛玻璃和 Material You。
 * 工具列表来自 [ToolboxCatalog]，目前只有账号密码本。
 */
class ToolboxOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var bubble: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var mode = Mode.TOOLS
    private var pkg = ""
    private var launchedAt = 0L
    private var editingId: String? = null
    private var labelInput: EditText? = null
    private var userInput: EditText? = null
    private var passInput: EditText? = null
    private var pkgInput: EditText? = null

    private val tick = object : Runnable {
        override fun run() {
            if (shouldShow()) {
                ensureView()
            } else if (SystemClock.elapsedRealtime() - launchedAt > GRACE_MS && !anyClient()) {
                stopSelf()
                return
            } else {
                removeView()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pkg = intent?.getStringExtra("pkg")?.takeIf { it.isNotBlank() } ?: ToolboxController.lastPackage(this)
        if (intent?.hasExtra("pkg") == true) launchedAt = SystemClock.elapsedRealtime()
        if (launchedAt == 0L) launchedAt = SystemClock.elapsedRealtime()
        if (!ToolboxController.enabled(this) || !OemAdapter.canDrawOverlay(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        ToolboxController.onVaultAuthedListener = { rebuild() }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (ToolboxController.onVaultAuthedListener != null) ToolboxController.onVaultAuthedListener = null
        removeView()
        super.onDestroy()
    }

    private fun shouldShow(): Boolean {
        if (!ToolboxController.enabled(this) || !OemAdapter.canDrawOverlay(this)) return false
        if (clientVisible()) return true
        return SystemClock.elapsedRealtime() - launchedAt < GRACE_MS && pkg.isNotBlank()
    }

    private fun clientVisible(): Boolean = processes().any { p ->
        p.processName.startsWith(packageName + ":p") &&
            p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun anyClient(): Boolean = processes().any { it.processName.startsWith(packageName + ":p") }

    private fun processes(): List<ActivityManager.RunningAppProcessInfo> = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView() {
        if (root != null) return
        val window = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = window
        val pal = Palette(this)
        val bubbleSize = dp(56)
        val bubbleView = FrameLayout(this).apply {
            background = circle(pal.accent)
            contentDescription = "工具箱"
            val icon = ImageView(this@ToolboxOverlayService).apply {
                setImageResource(R.drawable.ic_toolbox)
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
            }
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        bubble = bubbleView
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(pal)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        card = panel
        val holder = FrameLayout(this)
        holder.addView(bubbleView, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        holder.addView(
            panel,
            FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.WRAP_CONTENT),
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
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = 48
            } catch (_: Throwable) {
            }
        }
        lp = params
        bubbleView.setOnTouchListener(dragListener { toggle() })
        try {
            window.addView(holder, params)
        } catch (_: Throwable) {
            root = null
            card = null
            bubble = null
        }
    }

    private fun removeView() {
        val view = root ?: return
        root = null
        card = null
        bubble = null
        try {
            wm?.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun toggle() {
        expanded = !expanded
        if (!expanded) {
            mode = Mode.TOOLS
            VaultStore.lock()
            focusable(false)
        }
        applyVisibility()
    }

    private fun applyVisibility() {
        bubble?.visibility = if (expanded) View.GONE else View.VISIBLE
        card?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) rebuild()
        val params = lp ?: return
        val view = root ?: return
        try {
            wm?.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun focusable(on: Boolean) {
        val params = lp ?: return
        val view = root ?: return
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        params.flags = if (on) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } catch (_: Throwable) {
            }
        }
        try {
            wm?.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun rebuild() {
        val panel = card ?: return
        if (!expanded) return
        panel.removeAllViews()
        when (mode) {
            Mode.TOOLS -> buildTools(panel)
            Mode.VAULT -> buildVault(panel)
            Mode.EDIT -> buildEdit(panel)
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

    private fun buildTools(panel: LinearLayout) {
        focusable(false)
        panel.addView(header("工具箱", "收起") { toggle() })
        val hint = TextView(this).apply {
            text = if (pkg.isBlank()) "分身运行中" else "当前分身 $pkg"
            setTextColor(Palette(this@ToolboxOverlayService).sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(hint)
        for (tool in ToolboxCatalog.tools()) {
            panel.addView(toolRow(tool.title, tool.summary) {
                if (tool.id == "vault") {
                    mode = Mode.VAULT
                    rebuild()
                } else {
                    toast("这个工具还没接上")
                }
            })
        }
    }

    private fun buildVault(panel: LinearLayout) {
        focusable(false)
        panel.addView(header("账号密码本", "返回") {
            mode = Mode.TOOLS
            rebuild()
        })
        if (!VaultSession.valid()) {
            panel.addView(body("验证身份后才能查看和填入。密码用 Android Keystore 的 AES-GCM 加密，只保存在本机。"))
            panel.addView(button("验证身份") {
                try {
                    startActivity(
                        Intent(this, VaultAuthActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Throwable) {
                    toast("无法打开验证")
                }
            })
            return
        }
        val entries = try {
            VaultStore.list(this)
        } catch (_: Throwable) {
            VaultStore.lock()
            panel.addView(body("需要重新验证"))
            return
        }
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
        if (keys.isEmpty()) col.addView(body("还没有账号。添加后可以一键填入。"))
        for (key in keys) {
            val list = groups[key].orEmpty().sortedBy { it.label }
            val current = list.any { it.pkg == pkg }
            col.addView(section(if (current) "当前分身 · ${list.first().pkg}" else list.first().pkg))
            for (e in list) col.addView(entryRow(e))
        }
        scroll.addView(col)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)))
        panel.addView(link("锁定", {
            VaultStore.lock()
            rebuild()
        }))
    }

    private fun entryRow(e: VaultStore.Entry): LinearLayout {
        val pal = Palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            val title = TextView(this@ToolboxOverlayService).apply {
                text = e.label
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@ToolboxOverlayService).apply {
                text = e.username.ifBlank { "未填写用户名" }
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            addView(title)
            addView(sub)
            val row = LinearLayout(this@ToolboxOverlayService)
            row.addView(link("填入") { performFill(e) })
            row.addView(link("编辑") {
                editingId = e.id
                mode = Mode.EDIT
                rebuild()
            })
            row.addView(link("删除") { confirmDelete(e) })
            addView(row)
        }
    }

    private fun confirmDelete(e: VaultStore.Entry) {
        val panel = card ?: return
        panel.addView(body("删除「${e.label}」？"))
        panel.addView(button("确认删除") {
            try {
                VaultStore.delete(this, e.id)
            } catch (_: Throwable) {
                toast("删除失败，请重新验证")
            }
            rebuild()
        })
    }

    @SuppressLint("SetTextI18n")
    private fun buildEdit(panel: LinearLayout) {
        focusable(true)
        val existing = editingId?.let { id ->
            try {
                VaultStore.find(this, id)
            } catch (_: Throwable) {
                null
            }
        }
        panel.addView(header(if (existing == null) "添加账号" else "编辑账号", "取消") {
            mode = Mode.VAULT
            focusable(false)
            rebuild()
        })
        labelInput = field("名称，例如主号", existing?.label ?: "")
        userInput = field("用户名", existing?.username ?: "")
        passInput = field("密码", existing?.password ?: "", password = true)
        pkgInput = field("包名", existing?.pkg ?: pkg)
        panel.addView(labelInput)
        panel.addView(userInput)
        panel.addView(passInput)
        panel.addView(pkgInput)
        panel.addView(button("保存") { saveEdit(existing) })
    }

    private fun saveEdit(existing: VaultStore.Entry?) {
        val map = mapOf(
            "id" to (existing?.id ?: ""),
            "label" to (labelInput?.text?.toString() ?: ""),
            "user" to (userInput?.text?.toString() ?: ""),
            "pass" to (passInput?.text?.toString() ?: ""),
            "pkg" to (pkgInput?.text?.toString() ?: ""),
            "clone" to (existing?.cloneUid ?: if (pkg.isBlank()) "" else "w:$pkg"),
        )
        val r = try {
            VaultStore.upsert(this, map)
        } catch (e: VaultStore.Locked) {
            "locked"
        } catch (_: Throwable) {
            "error"
        }
        when (r) {
            "ok" -> {
                mode = Mode.VAULT
                focusable(false)
                rebuild()
            }
            "locked", "need_auth" -> {
                VaultStore.lock()
                mode = Mode.VAULT
                focusable(false)
                rebuild()
                toast("请重新验证")
            }
            else -> toast(if (r == "error") "保存失败" else r)
        }
    }

    private fun performFill(e: VaultStore.Entry) {
        // 先放弃焦点，避免无障碍把字填进悬浮窗自己的输入框。
        focusable(false)
        handler.postDelayed({
            val r = try {
                ToolboxController.fill(this, e.username, e.password)
            } catch (_: Throwable) {
                "error"
            }
            toast(fillMessage(r))
        }, 200)
    }

    private fun fillMessage(r: String) = when (r) {
        "filled" -> "已填入当前输入框"
        "copied" -> "未开启无障碍，已复制到剪贴板，30 秒后清除"
        "copied_no_field" -> "没有找到输入框，已复制到剪贴板，30 秒后清除"
        "locked", "need_auth" -> "请重新验证"
        else -> "填入失败"
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
            background = round(if (pal.night) 0x22FFFFFF else 0x11000000, dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gap.topMargin = dp(6)
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
                        else getSharedPreferences("toolbox", MODE_PRIVATE).edit().putInt("x", params.x).putInt("y", params.y).apply()
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle("工具箱悬浮窗")
            .setContentText(if (pkg.isBlank()) "分身运行时可以打开密码本" else "正在跟随 $pkg")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
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

    private fun round(color: Int, r: Float) = GradientDrawable().apply {
        cornerRadius = r
        setColor(color)
    }

    private fun panelBg(pal: Palette) = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(pal.bg)
        setStroke(dp(1).coerceAtLeast(1), pal.stroke)
    }

    private fun rowBg(pal: Palette) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(if (pal.night) 0x18FFFFFF else 0x14000000)
    }

    private enum class Mode { TOOLS, VAULT, EDIT }

    private class Palette(ctx: Context) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val accent = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        } else {
            0xFF2F7CF6.toInt()
        }
        val bg = if (night) 0xF01B1F2A.toInt() else 0xF7FFFFFF.toInt()
        val text = if (night) 0xFFF4F6FB.toInt() else 0xFF1B1F2A.toInt()
        val sub = if (night) 0xFFB7BECC.toInt() else 0xFF5C6575.toInt()
        val stroke = if (night) 0x33FFFFFF else 0x66FFFFFF
    }

    companion object {
        private const val CHANNEL = "toolbox_overlay"
        private const val NOTIFICATION_ID = 1004
        private const val GRACE_MS = 8_000L
    }
}
