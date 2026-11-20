package com.parallelspace.parallel_space

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import org.json.JSONObject
import java.io.File

/**
 * 从还在的磁盘目录、名称文件和已固定快捷方式里读回分身身份。
 * 只读，不创建、不搬移、不覆盖那些目录里的数据。
 */
object CloneRecovery {
    private data class Hit(val pkg: String, val userId: Int, val label: String)

    fun diskHasClones(ctx: Context): Boolean = scanDisk(ctx).isNotEmpty()

    fun scan(ctx: Context): List<Map<String, Any?>> {
        val merged = LinkedHashMap<String, Hit>()
        fun put(hit: Hit, preferLabel: Boolean) {
            if (!validPkg(hit.pkg) || hit.userId !in 0..63) return
            if (hit.pkg == ctx.packageName) return
            val key = "${hit.pkg}#${hit.userId}"
            val prev = merged[key]
            val label = hit.label.trim()
            merged[key] = when {
                prev == null -> Hit(hit.pkg, hit.userId, label)
                preferLabel && label.isNotEmpty() -> prev.copy(label = label)
                prev.label.isEmpty() && label.isNotEmpty() -> prev.copy(label = label)
                else -> prev
            }
        }
        for (hit in scanDisk(ctx)) put(hit, false)
        for (hit in scanNames(ctx)) put(hit, false)
        for (hit in scanShortcuts(ctx)) put(hit, true)
        return merged.values.map { mapOf("pkg" to it.pkg, "userId" to it.userId, "label" to it.label) }
    }

    private fun scanDisk(ctx: Context): List<Hit> {
        val parent = ctx.filesDir?.parentFile ?: return emptyList()
        val out = ArrayList<Hit>()
        for (rel in listOf("blackbox/data/user", "blackbox/data/user_de")) {
            val users = File(parent, rel).listFiles() ?: continue
            for (userDir in users) {
                if (!userDir.isDirectory) continue
                val userId = userDir.name.toIntOrNull() ?: continue
                val pkgs = userDir.listFiles() ?: continue
                for (pkgDir in pkgs) {
                    if (!pkgDir.isDirectory) continue
                    val children = pkgDir.list()
                    if (children == null || children.isEmpty()) continue
                    out += Hit(pkgDir.name, userId, "")
                }
            }
        }
        return out
    }

    private fun scanNames(ctx: Context): List<Hit> {
        val file = File(ctx.filesDir, CloneNames.FILE)
        if (!file.isFile) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val out = ArrayList<Hit>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val split = key.lastIndexOf('#')
                if (split <= 0) continue
                val pkg = key.substring(0, split)
                val userId = key.substring(split + 1).toIntOrNull() ?: continue
                out += Hit(pkg, userId, json.optString(key))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun scanShortcuts(ctx: Context): List<Hit> {
        return try {
            val pinned = ShortcutManagerCompat.getShortcuts(ctx, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            val out = ArrayList<Hit>()
            for (info in pinned) {
                val intent = info.intent ?: continue
                if (intent.component?.className != ShortcutTrampolineActivity::class.java.name) continue
                val pkg = intent.getStringExtra(Shortcuts.EXTRA_PKG) ?: continue
                val userId = CloneId.userId(intent.getStringExtra(MainActivity.EXTRA_CLONE_ID))
                out += Hit(pkg, userId, info.shortLabel?.toString() ?: "")
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun validPkg(pkg: String): Boolean =
        pkg.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))
}
