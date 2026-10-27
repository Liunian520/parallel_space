package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit

/**
 * 前台服务：周期性向 GPS / Network 测试 Provider 推送模拟坐标（系统级生效）。
 * 所有状态只在主线程读写（Handler 主 Looper + 生命周期回调），避免竞态。
 */
class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lat = 0.0
    private var lng = 0.0
    private var bearing = 0f
    private var speed = 0f
    private var lastNotify = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                push(this@MockLocationService, lat, lng, bearing, speed)
            } catch (e: SecurityException) {
                // 用户在开发者选项中取消了“模拟位置应用”
                stopSelf()
                return
            } catch (e: IllegalArgumentException) {
                // Provider 被其他应用移除：尝试重新注册
                try {
                    ensureProviders(this@MockLocationService)
                } catch (_: Exception) {
                    stopSelf()
                    return
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent != null && intent.hasExtra("lat")) {
            lat = intent.getDoubleExtra("lat", 0.0)
            lng = intent.getDoubleExtra("lng", 0.0)
            bearing = 0f
            speed = 0f
        } else if (prefs.contains("lat")) {
            // START_STICKY 被系统重启（intent 为 null）：恢复上次坐标，而不是推送 (0,0)
            lat = java.lang.Double.longBitsToDouble(prefs.getLong("lat", 0))
            lng = java.lang.Double.longBitsToDouble(prefs.getLong("lng", 0))
        } else {
            stopSelf()
            return START_NOT_STICKY
        }
        saveLast(this, lat, lng)
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限（ForegroundServiceStartNotAllowedException）
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ensureProviders(this)
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        val changed = !running
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (changed) notifyChanged(this)
        return START_STICKY
    }

    /** 仅更新坐标（路线回放）。 */
    private fun move(newLat: Double, newLng: Double, b: Float, s: Float) {
        lat = newLat
        lng = newLng
        bearing = b
        speed = s
        try {
            push(this, lat, lng, bearing, speed)
        } catch (_: Exception) {
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify > 5000) { // 通知节流
            lastNotify = now
            saveLast(this, lat, lng)
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (instance === this) instance = null
        running = false
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in PROVIDERS) {
            try {
                lm.setTestProviderEnabled(p, false)
                lm.removeTestProvider(p)
            } catch (_: Exception) {
            }
        }
        notifyChanged(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "虚拟定位", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("虚拟定位运行中（全局生效）")
            .setContentText(String.format(java.util.Locale.US, "%.5f, %.5f · 所有应用都会读取到此位置", lat, lng))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        @Volatile var running = false
        private var instance: MockLocationService? = null
        /** 状态变化回调（MainActivity 转发给 Flutter）。 */
        var onStateChanged: (() -> Unit)? = null

        const val ACTION_STOP = "com.parallelspace.parallel_space.STOP_MOCK"
        private const val PREFS = "mock_location"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1001
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        fun update(ctx: Context, lat: Double, lng: Double, bearing: Float, speed: Float) {
            if (!lat.isFinite() || !lng.isFinite()) return
            instance?.move(lat, lng, bearing, speed) ?: saveLast(ctx, lat, lng)
        }

        fun saveLast(ctx: Context, lat: Double, lng: Double) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong("lat", java.lang.Double.doubleToRawLongBits(lat))
                putLong("lng", java.lang.Double.doubleToRawLongBits(lng))
            }
        }

        fun lastLocation(ctx: Context): Pair<Double, Double>? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains("lat")) return null
            return java.lang.Double.longBitsToDouble(p.getLong("lat", 0)) to java.lang.Double.longBitsToDouble(p.getLong("lng", 0))
        }

        private fun notifyChanged(ctx: Context) {
            onStateChanged?.invoke()
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, MockTileService::class.java))
            } catch (_: Exception) {
            }
        }

        // Criteria.POWER_LOW / ACCURACY_FINE 与 ProviderProperties.POWER_USAGE_LOW / ACCURACY_FINE 数值相同（均为 1），
        // 后者需要 API 31，这里统一使用兼容写法。
        @SuppressLint("WrongConstant")
        @Suppress("DEPRECATION")
        fun ensureProviders(ctx: Context) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in PROVIDERS) {
                try {
                    lm.removeTestProvider(p)
                } catch (e: IllegalArgumentException) {
                    // 未注册过，忽略
                }
                lm.addTestProvider(
                    p, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
            }
        }

        fun push(ctx: Context, lat: Double, lng: Double, bearing: Float = 0f, speed: Float = 0f) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in PROVIDERS) {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 30.0
                    accuracy = 5f
                    this.speed = speed
                    this.bearing = bearing
                    if (Build.VERSION.SDK_INT >= 26) {
                        bearingAccuracyDegrees = 5f
                        speedAccuracyMetersPerSecond = 0.5f
                        verticalAccuracyMeters = 3f
                    }
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                lm.setTestProviderLocation(p, loc)
            }
        }
    }
}
