package com.parallelspace.parallel_space

import android.content.Context
import androidx.fragment.app.FragmentActivity

/**
 * 1.3.6 起密码本不再要求生物识别 / 锁屏。保留通道方法，直接返回 ok。
 */
object VaultAuth {
    fun deviceSecure(ctx: Context): Boolean = true

    fun prompt(activity: FragmentActivity, done: (String) -> Unit) {
        done("ok")
    }
}

/** 旧会话对象：始终视为已解锁，兼容残留调用。 */
object VaultSession {
    fun open(seconds: Int = 60) {}
    fun valid(): Boolean = true
    fun close() {}
    fun touch() {}
}
