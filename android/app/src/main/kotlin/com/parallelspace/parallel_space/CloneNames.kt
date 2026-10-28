package com.parallelspace.parallel_space

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import org.json.JSONObject
import java.io.File

/**
 * 每个分身实例的显示名（自定义名 / 默认名）。
 *
 * - 写到 filesDir/clone_names.json，键为 `包名#userId`。分身进程里的
 *   [TaskLabelCompat] 读它，把最近任务里的「[B0]应用名」换成这里的名字。
 * - 同时用 ShortcutManager.updateShortcuts 刷新已固定快捷方式的名称。
 */
object CloneNames {
    const val FILE = "clone_names.json"

    fun key(pkg: String, userId: Int) = "$pkg#$userId"

    /** items: [{pkg, userId, uid, label}] */
    fun sync(ctx: Context, items: List<Map<String, Any?>>): String {
        val app = ctx.applicationContext
        val json = JSONObject()
        for (m in items) {
            val pkg = m["pkg"] as? String ?: continue
            val uid = (m["userId"] as? Number)?.toInt() ?: 0
            val label = (m["label"] as? String)?.trim().orEmpty()
            if (label.isNotEmpty()) json.put(key(pkg, uid), label)
        }
        try {
            val f = File(app.filesDir, FILE)
            val tmp = File(app.filesDir, "$FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
            f.setReadable(true, false)
        } catch (t: Throwable) {
            ToolboxCrashLog.append(app, "WARN", "clone names write failed", t)
        }
        updatePinned(app, items)
        return "ok"
    }

    private fun updatePinned(ctx: Context, items: List<Map<String, Any?>>) {
        try {
            val pinned = ShortcutManagerCompat.getShortcuts(ctx, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .associateBy { it.id }
            if (pinned.isEmpty()) return
            val updates = ArrayList<ShortcutInfoCompat>()
            for (m in items) {
                val pkg = m["pkg"] as? String ?: continue
                val userId = (m["userId"] as? Number)?.toInt() ?: 0
                val label = (m["label"] as? String)?.trim().orEmpty()
                if (label.isEmpty()) continue
                val sid = Shortcuts.shortcutId(pkg, userId)
                val old = pinned[sid] ?: continue
                if (old.shortLabel?.toString() == label.take(24)) continue
                val cloneId = (m["uid"] as? String) ?: CloneId.of(pkg, userId)
                updates += ShortcutInfoCompat.Builder(ctx, sid)
                    .setShortLabel(label.take(24))
                    .setLongLabel(label)
                    .setIntent(Shortcuts.trampolineIntent(ctx, cloneId, pkg))
                    .build()
            }
            if (updates.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(ctx, updates)
        } catch (t: Throwable) {
            ToolboxCrashLog.append(ctx, "WARN", "update pinned shortcut labels failed", t)
        }
    }
}
