package com.parallelspace.parallel_space

import android.app.Activity
import android.os.Bundle

/**
 * Android 8+ 创建工作资料后，系统在新资料中启动 ACTION_PROVISIONING_SUCCESSFUL。
 * 部分 ROM（含 HyperOS）不保证发送 PROFILE_PROVISIONING_COMPLETE 广播，因此在这里也完成初始化。
 */
class ProvisioningDoneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            CloneAdminReceiver.setup(this)
        } catch (_: Exception) {
        }
        setResult(RESULT_OK)
        finish()
    }
}
