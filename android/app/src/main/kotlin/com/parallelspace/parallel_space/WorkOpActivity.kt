package com.parallelspace.parallel_space

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * 运行在工作资料内的无界面 Activity：接收个人空间 Alter Ego 发来的操作并以资料所有者身份执行。
 * 受 signature 级权限保护，只有同签名的 Alter Ego 可以调用。
 */
class WorkOpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val res = try {
            handle(intent)
        } catch (e: SecurityException) {
            "not_owner"
        } catch (e: Exception) {
            "error"
        }
        setResult(if (res == "ok") RESULT_OK else RESULT_FIRST_USER, Intent().putExtra(EXTRA_RESULT, res))
        finish()
    }

    private fun handle(i: Intent?): String {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isProfileOwnerApp(packageName)) return "not_owner"
        val admin = CloneAdminReceiver.admin(this)
        if (i?.getStringExtra(EXTRA_OP) == OP_WIPE) {
            // 资料所有者删除整个工作资料（含其中全部分身与数据）
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, "ok"))
            dpm.wipeData(0)
            return "ok"
        }
        val pkg = i?.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() && it != packageName } ?: return "error"
        return when (i.getStringExtra(EXTRA_OP)) {
            OP_SUSPEND -> {
                val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), i.getBooleanExtra(EXTRA_FLAG, false))
                if (failed.isEmpty()) "ok" else "error"
            }
            OP_CLONE -> clonePkg(dpm, admin, pkg)
            // 从工作资料移除：隐藏应用（数据保留，可再次“复制”恢复）
            OP_HIDE -> if (dpm.setApplicationHidden(admin, pkg, true)) "ok" else "error"
            else -> "error"
        }
    }

    private fun isInstalledHere(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun clonePkg(dpm: DevicePolicyManager, admin: android.content.ComponentName, pkg: String): String {
        try {
            if (dpm.isApplicationHidden(admin, pkg)) {
                dpm.setApplicationHidden(admin, pkg, false)
                if (isInstalledHere(pkg)) return "ok"
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                if (dpm.installExistingPackage(admin, pkg)) return "ok"
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: Exception) {
            }
        }
        try {
            dpm.enableSystemApp(admin, pkg)
            if (isInstalledHere(pkg)) return "ok"
        } catch (_: Exception) {
        }
        return if (isInstalledHere(pkg)) "ok" else "rejected"
    }

    companion object {
        const val ACTION = "com.parallelspace.parallel_space.action.WORK_OP"
        const val EXTRA_OP = "op"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_FLAG = "flag"
        const val EXTRA_RESULT = "result"
        const val OP_SUSPEND = "suspend"
        const val OP_CLONE = "clone"
        const val OP_HIDE = "hide"
        const val OP_WIPE = "wipe"
    }
}
