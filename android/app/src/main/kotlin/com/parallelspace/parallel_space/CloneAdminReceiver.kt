package com.parallelspace.parallel_space

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/**
 * 工作资料的资料所有者（Profile Owner）。
 * 资料创建完成后（运行在工作资料内的本 App 实例中）：命名、启用资料，并开放跨资料操作入口。
 */
class CloneAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        setup(context)
    }

    companion object {
        fun admin(ctx: Context) = ComponentName(ctx, CloneAdminReceiver::class.java)

        fun setup(ctx: Context) {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isProfileOwnerApp(ctx.packageName)) return
            val admin = admin(ctx)
            try {
                dpm.setProfileName(admin, "Alter Ego")
            } catch (_: Exception) {
            }
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, WorkOpActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, ProfileOpActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            try {
                dpm.clearCrossProfileIntentFilters(admin)
                dpm.addCrossProfileIntentFilter(
                    admin,
                    IntentFilter(WorkOpActivity.ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                    DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
                )
                dpm.addCrossProfileIntentFilter(
                    admin,
                    IntentFilter(ProfileOpActivity.ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                    DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
                )
            } catch (_: Exception) {
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    dpm.setCrossProfilePackages(admin, setOf(ctx.packageName))
                } catch (_: Exception) {
                }
            }
            try {
                dpm.setProfileEnabled(admin)
            } catch (_: Exception) {
            }
        }

        const val HANDSHAKE_NAME = "Alter Ego"
    }
}
