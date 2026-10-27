package com.parallelspace.parallel_space

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Calendar

/** UsageStatsManager 封装：需用户在系统设置中授予“使用情况访问权限”。 */
object UsageHelper {
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 今日（本地 0 点起）可启动应用的前台时长。 */
    fun today(ctx: Context, launchable: List<Map<String, String>>): Map<String, Map<String, Any>> {
        if (!hasAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val labels = launchable.associate { it["pkg"]!! to (it["label"] ?: it["pkg"]!!) }
        val out = HashMap<String, Map<String, Any>>()
        for ((pkg, s) in usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())) {
            val label = labels[pkg] ?: continue
            out[pkg] = mapOf("fg" to s.totalTimeInForeground, "last" to s.lastTimeUsed, "label" to label)
        }
        return out
    }
}
