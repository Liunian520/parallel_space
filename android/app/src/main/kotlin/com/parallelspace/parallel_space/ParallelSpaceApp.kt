package com.parallelspace.parallel_space

import android.app.Application
import android.content.Context

/**
 * 初始化 BlackBox 虚拟引擎（NewBlackbox / Bcore）。
 * 失败时 [VirtualEngine.available] 为 false，UI 会诚实提示。
 */
class ParallelSpaceApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        VirtualEngine.attach(base)
    }

    override fun onCreate() {
        super.onCreate()
        VirtualEngine.create(this)
        try {
            ToolboxCrashLog.installHandler(this, "app")
            ToolboxController.install(this)
        } catch (_: Throwable) {
        }
    }
}
