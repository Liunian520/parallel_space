package com.parallelspace.parallel_space

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * 悬浮窗没有 Activity，系统生物识别对话框需要一个跳板。
 * 这是一次性的透明页，不会把平行空间从最近任务里隐藏。
 */
class VaultAuthActivity : FragmentActivity() {
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultAuth.prompt(this) { code ->
            if (finished) return@prompt
            finished = true
            if (code == "ok") ToolboxController.onVaultAuthed()
            if (!isFinishing) finish()
            overridePendingTransition(0, 0)
        }
    }
}
