package com.parallelspace.parallel_space

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import org.json.JSONArray

/** 固定到桌面的快捷方式（含 MIUI / HyperOS 适配）。 */
object Shortcuts {
    const val EXTRA_PKG = "pkg"
    private const val ICON_PX = 192

    /**
     * 稳定且唯一的快捷方式 id：按「包名 + userId」区分实例。
     * userId=0 保持旧格式 clone_<包名>，已固定的老快捷方式不失效。
     */
    fun shortcutId(pkg: String, userId: Int = 0): String {
        val base = "clone_" + pkg.replace(Regex("[^A-Za-z0-9._]"), "_")
        return if (userId == 0) base else "${base}_u$userId"
    }

    /** 打开分身的跳板 Intent：显式组件 + ACTION_VIEW + NEW_TASK|CLEAR_TASK。 */
    fun trampolineIntent(ctx: Context, cloneId: String, pkg: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(ctx, ShortcutTrampolineActivity::class.java)
            data = "parallelspace://clone/${Uri.encode(cloneId.ifBlank { pkg })}".toUri()
            putExtra(MainActivity.EXTRA_CLONE_ID, cloneId)
            putExtra(EXTRA_PKG, pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    /** 虚拟分身用个人空间里的原图标；只有工作资料回退才取带公文包角标的图标。 */
    private fun icon(ctx: Context, pkg: String, px: Int): IconCompat {
        val bmp = iconBitmap(ctx, pkg, px)
        return if (bmp != null) IconCompat.createWithBitmap(bmp) else IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
    }

    private fun iconBitmap(ctx: Context, pkg: String, px: Int): android.graphics.Bitmap? {
        val virtual = VirtualEngine.available
        return if (virtual) {
            WorkProfile.iconBitmap(ctx, pkg, false, px)
        } else {
            WorkProfile.iconBitmap(ctx, pkg, true, px) ?: WorkProfile.iconBitmap(ctx, pkg, false, px)
        }
    }

    fun isPinned(ctx: Context, sid: String): Boolean = try {
        ShortcutManagerCompat.getShortcuts(ctx, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.id == sid }
    } catch (_: Exception) {
        false
    }

    /**
     * 'requested' | 'exists' | 'perm_denied' | 'unsupported' | 'no_profile' | 'error'
     * perm_denied：国产 ROM 的“桌面快捷方式”权限未开启（系统会静默丢弃请求）。
     */
    fun pin(ctx: Context, cloneId: String, pkg: String, label: String, force: Boolean): Map<String, Any?> {
        val sid = shortcutId(pkg, CloneId.userId(cloneId))
        fun r(v: String) = mapOf("r" to v, "sid" to sid, "rom" to OemAdapter.rom)
        // 虚拟引擎不依赖工作资料。只有两条路都没有时才拒绝。
        if (!VirtualEngine.available && WorkProfile.ourUser(ctx) == null) return r("no_profile")
        if (!force && OemAdapter.shortcutPermission(ctx) == false) return r("perm_denied")
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            return r(if (OemAdapter.shortcutNeedsPermission) "perm_denied" else "unsupported")
        }
        val info = ShortcutInfoCompat.Builder(ctx, sid)
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(icon(ctx, pkg, ICON_PX))
            .setIntent(trampolineIntent(ctx, cloneId, pkg))
            .build()
        if (isPinned(ctx, sid)) {
            ShortcutManagerCompat.updateShortcuts(ctx, listOf(info))
            return r("exists")
        }
        val cb = PendingIntent.getBroadcast(
            ctx, sid.hashCode(),
            Intent(ctx, PinResultReceiver::class.java).putExtra("sid", sid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return if (ShortcutManagerCompat.requestPinShortcut(ctx, info, cb.intentSender)) r("requested") else r("unsupported")
    }

    /** 国产启动器仍支持的旧式广播（Android 8+ 的原生启动器会忽略）。 */
    fun pinLegacy(ctx: Context, cloneId: String, pkg: String, label: String): String {
        val bmp = iconBitmap(ctx, pkg, 144)
        @Suppress("DEPRECATION")
        val i = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, trampolineIntent(ctx, cloneId, pkg))
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            .putExtra("duplicate", false)
        @Suppress("DEPRECATION")
        if (bmp != null) {
            i.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp)
        } else {
            i.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(ctx, R.mipmap.ic_launcher))
        }
        ctx.sendBroadcast(i)
        return "sent"
    }

    /** 打开本 ROM 的“桌面快捷方式”权限页（回退到应用详情）。 */
    fun openShortcutPermission(act: Activity): String =
        OemAdapter.launchFirst(act, OemAdapter.shortcutPermissionIntents(act))

    var onPinned: ((String) -> Unit)? = null
}

/** requestPinShortcut 的成功回调（用户在系统弹窗中确认后触发）。 */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sid = intent.getStringExtra("sid") ?: return
        Shortcuts.onPinned?.invoke(sid)
    }
}

/**
 * 桌面快捷方式 / 小组件的跳板：无锁的分身直接用虚拟引擎启动（不拉起平行空间界面）；
 * 引擎不可用时才回退工作资料。有指纹/密码锁、私密、已冻结或无法直接启动时，转交 MainActivity。
 * 绝不在个人空间启动同名应用。
 */
class ShortcutTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(Shortcuts.EXTRA_PKG)
        val id = intent?.getStringExtra(MainActivity.EXTRA_CLONE_ID)
        val userId = CloneId.userId(id)
        val direct = pkg != null && CloneGate.canLaunchDirectly(this, pkg, id)
        val ok = direct && launchClone(pkg!!, userId)
        if (!ok) {
            val fwd = MainActivity.cloneLaunchIntent(this, id ?: (pkg?.let { "w:$it" } ?: ""))
            if (Build.VERSION.SDK_INT >= 34) fwd.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(fwd)
        }
        finish()
    }

    private fun launchClone(pkg: String, userId: Int): Boolean = if (VirtualEngine.available) {
        VirtualEngine.launch(pkg, userId)["r"] == "ok"
    } else {
        WorkProfile.launch(this, pkg) == "ok"
    }
}

/** 读取 Flutter 端保存的分身设置（shared_preferences 旧版存储：FlutterSharedPreferences / flutter.*）。 */
object CloneGate {
    fun canLaunchDirectly(ctx: Context, pkg: String, id: String? = null): Boolean = try {
        val raw = ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getString("flutter.clones.v1", null) ?: return false
        // 兼容 shared_preferences 可能添加的前缀
        val start = raw.indexOf('[')
        if (start < 0) return false
        val arr = JSONArray(raw.substring(start))
        var res = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("pkg") != pkg) continue
            // 多实例：有 id 时只看这个实例
            if (!id.isNullOrBlank() && o.optString("uid").isNotBlank() && o.optString("uid") != id) continue
            res = !o.optBoolean("fp") && !o.optBoolean("pw") && !o.optBoolean("priv") && !o.optBoolean("frozen")
            break
        }
        res
    } catch (_: Exception) {
        false
    }
}

/** 分身实例 id：userId=0 为 `w:<包名>`，其余为 `w:<包名>#<userId>`。 */
object CloneId {
    fun userId(id: String?): Int {
        if (id.isNullOrBlank()) return 0
        val i = id.lastIndexOf('#')
        return if (i < 0) 0 else id.substring(i + 1).toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun pkg(id: String?): String {
        if (id.isNullOrBlank()) return ""
        val body = if (id.startsWith("w:")) id.substring(2) else id
        val i = body.lastIndexOf('#')
        return if (i < 0) body else body.substring(0, i)
    }

    fun of(pkg: String, userId: Int): String = if (userId == 0) "w:$pkg" else "w:$pkg#$userId"
}
