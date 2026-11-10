package com.parallelspace.parallel_space

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import top.niunaijun.blackbox.BlackBoxCore

/**
 * 分身 Activity 状态栏 / 刘海 / 导航栏重叠修复。
 *
 * 1.3.30 对非全屏页调用 `decorFitsSystemWindows=true`，并给 BTheme 加
 * `windowOptOutEdgeToEdgeEnforcement`。在 Android 16（REDMI K80 HyperOS 3.0.309.0
 * 的固件是 `user-16.0`）上这两步都不会移动客户界面：
 *
 * - Bcore 在 `callActivityOnCreate` 之前用客户 ActivityInfo 换掉主题
 *   （`AppInstrumentation.checkActivity` → `ActivityCompat.fix`）。BTheme 不是客户窗口的主题。
 *   targetSdk 36 在 API 36 上该 opt-out 属性本身也被系统禁用。
 * - AOSP `PhoneWindow.setDecorFitsSystemWindows` 在 `mEdgeToEdgeEnforced` 时直接 return。
 * - `beforeMainActivityOnCreate` / `afterMainActivityOnCreate` 在当前 bcore AAR 里没有调用点。
 *   真正会跑的是 `BaseInstrumentationDelegate.callActivityOnCreate/Resume`，且发生在
 *   客户 `onCreate` / `onResume` 之后。
 * - 客户主题若 `windowFullscreen`，`ActivityCompat.fix` 会先加上 `FLAG_FULLSCREEN`。
 *   1.3.30 把它当成沉浸式直接跳过。edge-to-edge 下状态栏图标仍然可见，顶栏就和状态栏同一行。
 *
 * 状态栏仍可见时，给 `android.R.id.content` 加上 status/cutout/nav insets 的 padding，
 * 并吃掉这些 insets，避免客户自己再加一遍。状态栏实际隐藏（游戏/视频）不加 padding，
 * 也不吞 insets。决策在 [CloneInsetPolicy]，不依赖 `FLAG_FULLSCREEN`。
 */
object CloneWindowFix {
    private const val TAG = "CloneWinFix"
    @Volatile private var lifecycleAdded = false
    private val insetLogged = HashSet<String>()

    fun install() {
        try {
            if (!BlackBoxCore.get().isBlackProcess) return
        } catch (_: Throwable) { return }
        if (lifecycleAdded) return
        lifecycleAdded = true
        try {
            BlackBoxCore.get().addAppLifecycleCallback(object :
                top.niunaijun.blackbox.app.configuration.AppLifecycleCallback() {
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                    apply(activity, "created")
                }
                override fun onActivityResumed(activity: Activity) {
                    apply(activity, "resume")
                }
            })
            Log.i(TAG, "window inset fix installed")
        } catch (t: Throwable) {
            Log.w(TAG, "install", t)
        }
    }

    fun apply(activity: Activity, where: String) {
        try {
            val w = activity.window ?: return
            val enforced = readEdgeToEdgeEnforced(w)
            val manual = CloneInsetPolicy.useManualInsets(enforced, Build.VERSION.SDK_INT)
            val target = try { activity.applicationInfo.targetSdkVersion } catch (_: Throwable) { -1 }
            if (where == "created" || where == "resume") {
                CameraDiag.log(
                    "win@$where ${activity.javaClass.name} sdk=${Build.VERSION.SDK_INT} target=$target " +
                        "e2e=$enforced manual=$manual flags=0x${Integer.toHexString(w.attributes.flags)}"
                )
            }
            if (!manual) {
                if (isLegacyFullscreen(activity)) {
                    if (where == "created" || where == "resume") {
                        CameraDiag.log("win@$where ${activity.javaClass.simpleName} legacy fullscreen — skip insets")
                    }
                    return
                }
                applyLegacyDecorFits(w)
                return
            }
            val content = w.decorView.findViewById<View>(android.R.id.content)
            if (content == null) {
                CameraDiag.log("win@$where ${activity.javaClass.simpleName} no android.R.id.content")
                return
            }
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                onInsets(activity, v, insets)
            }
            if (content.isAttachedToWindow) {
                ViewCompat.requestApplyInsets(content)
            } else {
                content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        ViewCompat.requestApplyInsets(v)
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apply $where", t)
            CameraDiag.log("win@$where FAIL $t")
        }
    }

    private fun onInsets(activity: Activity, v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = CloneInsetPolicy.Bars(
            insets.isVisible(WindowInsetsCompat.Type.statusBars()),
            insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
            insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
            insets.getInsets(WindowInsetsCompat.Type.displayCutout()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.displayCutout()).left,
            insets.getInsets(WindowInsetsCompat.Type.displayCutout()).right,
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).left,
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).right,
        )
        val pad = CloneInsetPolicy.padding(bars)
        v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
        val key = activity.javaClass.name
        if (insetLogged.add(key)) {
            CameraDiag.log(
                "win@insets $key statusVisible=${bars.statusVisible} navVisible=${bars.navVisible} " +
                    "statusTop=${bars.statusTop} cutoutTop=${bars.cutoutTop} navBottom=${bars.navBottom} pad=$pad"
            )
        }
        if (!CloneInsetPolicy.consumeSystemBars(pad)) {
            return insets
        }
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        return WindowInsetsCompat.Builder(insets).setInsets(types, Insets.NONE).build()
    }

    /** API 34 及未强制 edge-to-edge 的窗口：decorFits 仍然有效，不要再叠加手动 padding。 */
    private fun applyLegacyDecorFits(w: Window) {
        WindowCompat.setDecorFitsSystemWindows(w, true)
        try {
            @Suppress("DEPRECATION")
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            @Suppress("DEPRECATION")
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        } catch (_: Throwable) {}
        val decor = w.decorView
        @Suppress("DEPRECATION")
        val ui = decor.systemUiVisibility
        @Suppress("DEPRECATION")
        val cleared = ui and (
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            ).inv()
        if (cleared != ui) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = cleared
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val lp = w.attributes
                if (lp.layoutInDisplayCutoutMode ==
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                ) {
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    w.attributes = lp
                }
            } catch (_: Throwable) {}
        }
    }

    private fun readEdgeToEdgeEnforced(window: Window): Boolean? {
        var c: Class<*>? = window.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("mEdgeToEdgeEnforced")
                f.isAccessible = true
                return f.getBoolean(window)
            } catch (_: Throwable) {}
            c = c.superclass
        }
        return null
    }

    /** 仅用于尚未强制 edge-to-edge 的系统。旗标为真且状态栏真被藏起时不改。 */
    private fun isLegacyFullscreen(activity: Activity): Boolean {
        val w = activity.window ?: return false
        val attrs = w.attributes
        @Suppress("DEPRECATION")
        if (attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) return true
        try {
            val ta = activity.obtainStyledAttributes(intArrayOf(android.R.attr.windowFullscreen))
            val full = ta.getBoolean(0, false)
            ta.recycle()
            if (full) return true
        } catch (_: Throwable) {}
        val decor = w.decorView
        @Suppress("DEPRECATION")
        val ui = decor.systemUiVisibility
        @Suppress("DEPRECATION")
        if (ui and View.SYSTEM_UI_FLAG_IMMERSIVE != 0) return true
        @Suppress("DEPRECATION")
        if (ui and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0) return true
        @Suppress("DEPRECATION")
        if (ui and View.SYSTEM_UI_FLAG_FULLSCREEN != 0 &&
            ui and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0
        ) return true
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val root = decor.rootWindowInsets
                if (root != null && !root.isVisible(android.view.WindowInsets.Type.statusBars())) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return false
    }
}
