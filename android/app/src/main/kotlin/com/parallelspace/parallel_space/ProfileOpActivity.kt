package com.parallelspace.parallel_space

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import java.io.File

/**
 * 无界面：仅在工作资料中以资料所有者身份执行 setup / clone / hide / wipe。
 * 必须由 CrossProfileApps 显式 ComponentName 启动；绝不是 MainActivity。
 * 跑完立即 finish，不启动 Flutter、不显示「切换到主空间」。
 */
class ProfileOpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            run()
        } catch (_: Throwable) {
        } finally {
            // 握手：个人空间可探测 linked / setupDone
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SETUP, true).putLong(KEY_TS, System.currentTimeMillis()).apply()
            } catch (_: Exception) {
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun run() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isProfileOwnerApp(packageName)) return
        CloneAdminReceiver.setup(this)
        val admin = CloneAdminReceiver.admin(this)
        when (intent?.getStringExtra(EXTRA_OP) ?: OP_SETUP) {
            OP_SETUP -> Unit
            OP_WIPE -> dpm.wipeData(0)
            OP_CLONE -> {
                val pkg = intent.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() && it != packageName } ?: return
                clonePkg(dpm, admin, pkg, intent.getStringExtra(EXTRA_APK))
            }
            OP_HIDE -> {
                val pkg = intent.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() && it != packageName } ?: return
                try {
                    dpm.setApplicationHidden(admin, pkg, true)
                } catch (_: Exception) {
                }
            }
            OP_SUSPEND -> {
                val pkg = intent.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() && it != packageName } ?: return
                try {
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), intent.getBooleanExtra(EXTRA_FLAG, false))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) {
        false
    }

    private fun clonePkg(dpm: DevicePolicyManager, admin: ComponentName, pkg: String, apkPath: String?) {
        try {
            if (dpm.isApplicationHidden(admin, pkg)) {
                dpm.setApplicationHidden(admin, pkg, false)
                if (isInstalled(pkg)) return
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                if (dpm.installExistingPackage(admin, pkg) && isInstalled(pkg)) return
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: Exception) {
            }
        }
        try {
            dpm.enableSystemApp(admin, pkg)
            if (isInstalled(pkg)) return
        } catch (_: Exception) {
        }
        if (!apkPath.isNullOrBlank()) tryInstallApk(apkPath)
    }

    private fun tryInstallApk(path: String) {
        if (Build.VERSION.SDK_INT < 21) return
        val file = File(path)
        if (!file.canRead() || file.length() <= 0L) return
        try {
            val pi = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val cb = PendingIntent.getBroadcast(
                    this, id, Intent("$packageName.INSTALL_RESULT").setPackage(packageName), flags,
                )
                session.commit(cb.intentSender)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION = "com.parallelspace.parallel_space.action.PROFILE_OP"
        const val EXTRA_OP = "op"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_FLAG = "flag"
        const val EXTRA_APK = "apk"
        const val OP_SETUP = "setup"
        const val OP_CLONE = "clone"
        const val OP_HIDE = "hide"
        const val OP_SUSPEND = "suspend"
        const val OP_WIPE = "wipe"
        const val PREFS = "profile_op"
        const val KEY_SETUP = "setup_done"
        const val KEY_TS = "setup_ts"

        fun component(ctx: Context) = ComponentName(ctx, ProfileOpActivity::class.java)

        /** 显式 ComponentName，禁止隐式解析到 MainActivity。 */
        fun intent(ctx: Context, op: String, pkg: String? = null, flag: Boolean = false, apk: String? = null): Intent =
            Intent(ACTION)
                .setComponent(component(ctx))
                .setPackage(ctx.packageName)
                .putExtra(EXTRA_OP, op)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_FLAG, flag)
                .putExtra(EXTRA_APK, apk)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY,
                )
    }
}
