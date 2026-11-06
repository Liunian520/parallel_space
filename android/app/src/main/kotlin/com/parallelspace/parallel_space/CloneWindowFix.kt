package com.parallelspace.parallel_space

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import top.niunaijun.blackbox.BlackBoxCore

/**
 * 分身 Activity 状态栏 / 刘海重叠修复（1.3.30）。
 *
 * 根因：Alter Ego 的 targetSdk 已是 36。Android 15+ 对 targetSdk≥35 的进程默认强制
 * edge-to-edge（内容画到状态栏下面）。分身跑在宿主进程身份下，也吃这套默认；
 * 多数被克隆 App 的布局并没按 WindowInsets 给顶部留白，于是顶栏按钮被状态栏挡住点不到。
 * 「切换扫码工具」之类顶部按钮无响应，往往也是同一原因。
 *
 * 做法：非全屏 / 非沉浸式 Activity 强制 decorFitsSystemWindows=true，清掉半透明状态栏
 * 与 LAYOUT_FULLSCREEN；游戏 / 视频等已自己全屏或 IMMERSIVE 的不改。
 */
object CloneWindowFix {
    private const val TAG = "CloneWinFix"
    @Volatile private var lifecycleAdded = false

    fun install() {
        try {
            if (!BlackBoxCore.get().isBlackProcess) return
        } catch (_: Throwable) { return }
        if (lifecycleAdded) return
        lifecycleAdded = true
        try {
            BlackBoxCore.get().addAppLifecycleCallback(object :
                top.niunaijun.blackbox.app.configuration.AppLifecycleCallback() {
                override fun beforeMainActivityOnCreate(activity: Activity) {
                    apply(activity, "beforeCreate")
                }
                override fun afterMainActivityOnCreate(activity: Activity) {
                    apply(activity, "afterCreate")
                }
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
            if (isImmersiveOrFullscreen(activity)) {
                if (where == "resume" || where == "afterCreate") {
                    CameraDiag.log("win@$where ${activity.javaClass.simpleName} immersive/fullscreen — skip insets")
                }
                return
            }
            // 关键心：内容不要画到系统栏下面
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

            // API 28+：非全屏时不要强制 shortEdges 把内容顶进刘海
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

            if (where == "afterCreate" || where == "created") {
                CameraDiag.log(
                    "win@$where ${activity.javaClass.simpleName} decorFits=true sdk=${Build.VERSION.SDK_INT}"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apply $where", t)
        }
    }

    /** 游戏 / 视频 / 扫码全屏页：自己要沉浸式时不要硬塞系统栏 padding。 */
    private fun isImmersiveOrFullscreen(activity: Activity): Boolean {
        val w = activity.window ?: return false
        val attrs = w.attributes
        @Suppress("DEPRECATION")
        if (attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) return true
        // 主题 windowFullscreen（Bcore ActivityCompat 也会据此加 FLAG_FULLSCREEN）
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
        // WindowInsetsController 隐藏了状态栏（API 30+）
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
