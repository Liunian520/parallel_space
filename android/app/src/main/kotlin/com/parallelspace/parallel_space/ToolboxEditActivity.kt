package com.parallelspace.parallel_space

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 账号编辑页：独立 Activity，不在悬浮窗里放 EditText。
 *
 * HyperOS 上 TYPE_APPLICATION_OVERLAY 内的输入框会因焦点/IME 切 flags 导致
 * 悬浮窗闪烁崩溃；1.3.11 起添加/编辑一律走本页。运行在 `:toolbox_edit`
 * 进程，崩溃不会拖垮主进程里的 [ToolboxOverlayService]。
 */
class ToolboxEditActivity : Activity() {
    private var entryId: String = ""
    private var bindPkg: String = ""
    private var appLabel: String = ""
    private var labelInput: EditText? = null
    private var userInput: EditText? = null
    private var passInput: EditText? = null
    private var passVisible = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToolboxCrashLog.installHandler(this, "edit")
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = 0.45f }
            if (Build.VERSION.SDK_INT >= 21) {
                window.statusBarColor = 0x00000000
                window.navigationBarColor = 0x00000000
            }
        } catch (_: Throwable) {
        }

        entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        bindPkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
            .ifBlank { ToolboxController.lastPackage(this) }
        appLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        val existing = if (entryId.isNotBlank()) {
            try {
                VaultStore.find(this, entryId)
            } catch (_: Throwable) {
                null
            }
        } else null
        if (existing != null) {
            bindPkg = existing.pkg.ifBlank { bindPkg }
        }
        if (appLabel.isBlank() && bindPkg.isNotBlank()) {
            appLabel = try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(bindPkg, 0)
                pm.getApplicationLabel(ai)?.toString().orEmpty()
            } catch (_: Throwable) {
                bindPkg
            }
        }

        ToolboxCrashLog.append(
            this,
            "INFO",
            "edit activity open id=${entryId.ifBlank { "new" }} pkg=$bindPkg process=${android.os.Process.myPid()}",
        )
        notifyOverlay(ACTION_EDIT_STARTED)

        setContentView(buildUi(existing))
    }

    override fun onDestroy() {
        if (!finished) {
            finished = true
            notifyOverlay(ACTION_EDIT_FINISHED)
        }
        super.onDestroy()
    }

    override fun finish() {
        if (!finished) {
            finished = true
            notifyOverlay(ACTION_EDIT_FINISHED)
        }
        super.finish()
        try {
            overridePendingTransition(0, android.R.anim.fade_out)
        } catch (_: Throwable) {
        }
        // 尽量把分身任务带回前台；失败也不影响关闭本页
        try {
            moveTaskToBack(true)
        } catch (_: Throwable) {
        }
    }

    private fun buildUi(existing: VaultStore.Entry?): View {
        val pal = Palette(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            setOnClickListener { /* 点遮罩不关，避免误触 */ }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                setColor(pal.bg)
                setStroke(dp(1).coerceAtLeast(1), pal.stroke)
            }
            elevation = dp(12).toFloat()
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = if (existing == null) "添加账号" else "编辑账号"
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "取消"
            setTextColor(pal.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(4), dp(10))
            minimumHeight = dp(44)
            setOnClickListener { finish() }
        })
        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = "名称、用户名和密码保存在本机，方便一键填入。输入在独立页面完成，不会影响悬浮窗。"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(10))
        })

        card.addView(appChip(pal))

        labelInput = field(pal, "名称，例如主号", existing?.label.orEmpty())
        userInput = field(pal, "用户名 / 手机 / 学号", existing?.username.orEmpty())
        passInput = field(
            pal,
            "密码",
            existing?.password.orEmpty(),
            password = true,
        )
        card.addView(labelInput)
        card.addView(userInput)

        val passRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gap = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            gap.topMargin = dp(8)
            layoutParams = gap
        }
        passRow.addView(
            passInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        passRow.addView(TextView(this).apply {
            text = "显示"
            setTextColor(pal.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(12), dp(4), dp(12))
            minimumHeight = dp(44)
            setOnClickListener {
                passVisible = !passVisible
                text = if (passVisible) "隐藏" else "显示"
                val et = passInput ?: return@setOnClickListener
                val start = et.selectionStart
                et.inputType = if (passVisible) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                et.typeface = Typeface.DEFAULT
                try {
                    if (start >= 0) et.setSelection(start.coerceAtMost(et.text?.length ?: 0))
                } catch (_: Throwable) {
                }
            }
        })
        card.addView(passRow)

        card.addView(TextView(this).apply {
            text = "保存"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(pal.accent)
            }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            minimumHeight = dp(48)
            val gap = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            gap.topMargin = dp(16)
            layoutParams = gap
            setOnClickListener { save(existing) }
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(card)
        }
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        return root
    }

    private fun appChip(pal: Palette): LinearLayout {
        val icon = try {
            if (bindPkg.isNotBlank()) packageManager.getApplicationIcon(bindPkg) else null
        } catch (_: Throwable) {
            null
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (pal.night) 0x22FFFFFF.toInt() else 0x11000000.toInt())
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val gap = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            gap.bottomMargin = dp(4)
            layoutParams = gap
            addView(ImageView(this@ToolboxEditActivity).apply {
                val s = dp(32)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(10) }
                if (icon != null) setImageDrawable(icon)
                else setImageResource(R.drawable.ic_toolbox)
            })
            addView(LinearLayout(this@ToolboxEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ToolboxEditActivity).apply {
                    text = appLabel.ifBlank { bindPkg.ifBlank { "未知应用" } }
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@ToolboxEditActivity).apply {
                    text = "自动绑定当前分身"
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                })
            })
        }
    }

    private fun field(
        pal: Palette,
        hint: String,
        value: String,
        password: Boolean = false,
    ): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (pal.night) 0x18FFFFFF.toInt() else 0x0A000000.toInt())
                setStroke(
                    dp(1).coerceAtLeast(1),
                    if (pal.night) 0x44FFFFFF.toInt() else 0x332F7CF6.toInt(),
                )
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(48)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (!password) {
                val gap = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                gap.topMargin = dp(8)
                layoutParams = gap
            }
        }
    }

    private fun save(existing: VaultStore.Entry?) {
        val map = mapOf(
            "id" to (existing?.id ?: entryId),
            "label" to (labelInput?.text?.toString() ?: ""),
            "user" to (userInput?.text?.toString() ?: ""),
            "pass" to (passInput?.text?.toString() ?: ""),
            "pkg" to bindPkg,
            "clone" to (existing?.cloneUid ?: if (bindPkg.isBlank()) "" else "w:$bindPkg"),
        )
        val r = try {
            // 跨进程：先清本进程缓存再写，写完发广播让主进程清缓存
            VaultStore.lock()
            VaultStore.upsert(this, map)
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "ERROR", "edit save failed", t)
            "error"
        }
        if (r == "ok") {
            ToolboxCrashLog.append(this, "INFO", "edit saved pkg=$bindPkg")
            notifyOverlay(ACTION_VAULT_CHANGED)
            try {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
            finish()
        } else {
            try {
                Toast.makeText(this, if (r == "error") "保存失败" else r, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun notifyOverlay(action: String) {
        try {
            sendBroadcast(
                Intent(action).setPackage(packageName),
            )
        } catch (t: Throwable) {
            ToolboxCrashLog.append(this, "WARN", "broadcast $action failed", t)
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private class Palette(ctx: Context) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = if (Build.VERSION.SDK_INT >= 31) {
            try {
                ctx.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            } catch (_: Throwable) {
                0xFF2F7CF6.toInt()
            }
        } else {
            0xFF2F7CF6.toInt()
        }
        val bg = if (night) 0xF21B1F2A.toInt() else 0xF7FFFFFF.toInt()
        val text = if (night) 0xFFF4F6FB.toInt() else 0xFF1B1F2A.toInt()
        val sub = if (night) 0xFFB7BECC.toInt() else 0xFF5C6575.toInt()
        val stroke = if (night) 0x40FFFFFF.toInt() else 0x55FFFFFF.toInt()
    }

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "app_label"
        const val ACTION_EDIT_STARTED = "com.parallelspace.parallel_space.action.TOOLBOX_EDIT_STARTED"
        const val ACTION_EDIT_FINISHED = "com.parallelspace.parallel_space.action.TOOLBOX_EDIT_FINISHED"
        const val ACTION_VAULT_CHANGED = "com.parallelspace.parallel_space.action.VAULT_CHANGED"

        fun open(ctx: Context, pkg: String, entryId: String? = null, appLabel: String = "") {
            val i = Intent(ctx, ToolboxEditActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_LABEL, appLabel)
                if (!entryId.isNullOrBlank()) putExtra(EXTRA_ENTRY_ID, entryId)
            }
            try {
                ctx.startActivity(i)
                ToolboxCrashLog.append(ctx, "INFO", "launch ToolboxEditActivity pkg=$pkg id=${entryId ?: "new"}")
            } catch (t: Throwable) {
                ToolboxCrashLog.append(ctx, "ERROR", "launch ToolboxEditActivity failed", t)
                try {
                    Toast.makeText(ctx, "无法打开编辑页", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
