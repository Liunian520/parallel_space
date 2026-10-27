package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** 快捷设置开关：开/关上次使用的模拟位置。 */
class MockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (MockLocationService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "虚拟定位"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (MockLocationService.running) "运行中" else "已关闭"
        }
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (MockLocationService.running) {
            stopService(Intent(this, MockLocationService::class.java))
            refresh()
            return
        }
        val last = MockLocationService.lastLocation(this)
        if (last == null) {
            openApp()
            return
        }
        try {
            MockLocationService.ensureProviders(this)
            ContextCompat.startForegroundService(
                this,
                Intent(this, MockLocationService::class.java).putExtra("lat", last.first).putExtra("lng", last.second),
            )
        } catch (e: SecurityException) {
            openApp() // 未被选为模拟位置应用：打开 App 查看引导
        } catch (e: Exception) {
            openApp() // 系统不允许从此处启动前台服务
        }
        refresh()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // Intent 版本仅在 API < 34 使用
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
