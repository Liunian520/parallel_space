package com.parallelspace.parallel_space

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 账号密码本唯一真相源：`filesDir/vault_plain.json`（原子写入）。
 *
 * 1.3.12：添加/编辑只在 Flutter 主进程完成；悬浮窗只读。
 * 旧版可能残留：SharedPreferences `vault_plain`、独立进程 `:toolbox_edit`
 * 写过的同名文件、以及更早的 `vault.enc`。加载时合并全部来源（文件优先），
 * 再写回文件，避免「保存了却看不到」。
 */
object VaultStore {
    data class Entry(
        val id: String,
        val pkg: String,
        val cloneUid: String,
        val label: String,
        val username: String,
        val password: String,
        val note: String,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "id" to id,
            "pkg" to pkg,
            "clone" to cloneUid,
            "label" to label,
            "user" to username,
            "pass" to password,
            "note" to note,
        )

        val groupKey: String get() = cloneUid.ifBlank { pkg }
    }

    /** @deprecated 1.3.14 起不再发系统广播（BlackBox AMS hook 会 NPE）；保留常量以免旧代码编译失败。 */
    const val ACTION_VAULT_CHANGED = "com.parallelspace.parallel_space.action.VAULT_CHANGED"

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.addIfAbsent(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    private const val PREFS = "vault_plain"
    private const val KEY_JSON = "entries_json"
    private const val KEY_MIGRATED = "migrated_enc"
    private const val KEY_MERGED_LEGACY = "merged_legacy_v12"
    const val FILE_JSON = "vault_plain.json"

    private val lock = Any()
    private var cache: MutableList<Entry>? = null

    /** 清内存缓存，强制下次从磁盘重读。 */
    fun invalidateCache() {
        synchronized(lock) { cache = null }
    }

    /** 兼容旧调用名（曾表示「上锁」；现仅清缓存）。 */
    fun lock() = invalidateCache()

    fun list(ctx: Context): List<Entry> = synchronized(lock) {
        load(ctx).toList()
    }

    /** @return "ok" 或错误说明 */
    fun upsert(ctx: Context, raw: Map<*, *>): String {
        val result = synchronized(lock) {
            val pkg = raw.str("pkg").trim().replace("\n", "")
            if (pkg.isEmpty() || pkg.length > 200) return@synchronized "请填写所属分身"
            val label = raw.str("label").trim().ifEmpty { "未命名" }.take(40)
            val user = raw.str("user").trim().take(200)
            val pass = raw.str("pass").take(200)
            val note = raw.str("note").trim().take(200)
            if (user.isEmpty() && pass.isEmpty()) return@synchronized "请至少填写用户名或密码"
            val clone = raw.str("clone").trim().take(220).ifBlank { "w:$pkg" }
            val items = load(ctx)
            if (items.size >= 300 && raw.str("id").isBlank()) return@synchronized "密码本已满"
            val id = raw.str("id").ifBlank { UUID.randomUUID().toString() }
            val next = Entry(id, pkg, clone, label, user, pass, note)
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = next else items.add(next)
            persist(ctx, items)
            "ok"
        }
        if (result == "ok") notifyChanged(ctx)
        return result
    }

    fun delete(ctx: Context, id: String): String {
        val result = synchronized(lock) {
            val items = load(ctx)
            val removed = items.removeAll { it.id == id }
            if (!removed) return@synchronized "not_found"
            persist(ctx, items)
            "ok"
        }
        if (result == "ok") notifyChanged(ctx)
        return result
    }

    fun find(ctx: Context, id: String): Entry? = synchronized(lock) {
        load(ctx).firstOrNull { it.id == id }
    }

    class Locked : IllegalStateException("locked")

    /**
     * 通知密码本变更。不用 [Context.sendBroadcast]：
     * BlackBox 在宿主进程也安装了 IActivityManagerProxy.BroadcastIntent，
     * Context.getClassLoader() 空指针会导致通知失败（1.3.11 诊断日志已证实）。
     */
    fun notifyChanged(ctx: Context) {
        invalidateCache()
        for (l in changeListeners) {
            try {
                l()
            } catch (_: Throwable) {
            }
        }
        // 文件已由 persist 写入；FileObserver 是跨组件兜底
        try {
            vaultFile(ctx).setLastModified(System.currentTimeMillis())
        } catch (_: Throwable) {
        }
    }

    fun vaultFile(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_JSON)

    /** 纯函数：合并多源条目，同 id 时后者覆盖前者。供测试与 load 共用。 */
    fun mergeById(vararg sources: List<Entry>): List<Entry> {
        val byId = LinkedHashMap<String, Entry>()
        for (src in sources) {
            for (e in src) {
                if (e.id.isBlank() || e.pkg.isBlank()) continue
                byId[e.id] = e
            }
        }
        return byId.values.toList()
    }

    fun parseEntries(raw: String?): MutableList<Entry> {
        val items = mutableListOf<Entry>()
        if (raw.isNullOrBlank()) return items
        try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val pkg = o.optString("pkg")
                if (id.isBlank() || pkg.isBlank()) continue
                items += Entry(
                    id = id,
                    pkg = pkg,
                    cloneUid = o.optString("clone"),
                    label = o.optString("label").ifBlank { "未命名" },
                    username = o.optString("user"),
                    password = o.optString("pass"),
                    note = o.optString("note"),
                )
            }
        } catch (_: Throwable) {
        }
        return items
    }

    private fun readFileText(ctx: Context): String? {
        val file = vaultFile(ctx)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            file.readText()
        } catch (_: Throwable) {
            null
        }
    }

    private fun readPrefsText(ctx: Context): String? {
        return try {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val fileItems = parseEntries(readFileText(ctx))
        val prefsItems = parseEntries(readPrefsText(ctx))
        // 文件优先：先 prefs 再 file，同 id 以文件为准
        val merged = mergeById(prefsItems, fileItems).toMutableList()
        val encItems = tryDecryptEnc(ctx)
        val all = mergeById(merged, encItems).toMutableList()
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needRewrite =
            all.isNotEmpty() && (
                fileItems.isEmpty() ||
                    prefsItems.any { p -> fileItems.none { it.id == p.id } } ||
                    encItems.isNotEmpty() ||
                    !prefs.getBoolean(KEY_MERGED_LEGACY, false)
                )
        if (needRewrite) {
            persist(ctx, all)
            try {
                prefs.edit().putBoolean(KEY_MERGED_LEGACY, true).putBoolean(KEY_MIGRATED, true).apply()
            } catch (_: Throwable) {
            }
            // enc 解出并写入后删除
            if (encItems.isNotEmpty()) {
                try {
                    File(ctx.applicationContext.filesDir, "vault.enc").delete()
                } catch (_: Throwable) {
                }
            }
        } else {
            cache = all
        }
        return all
    }

    private fun tryDecryptEnc(ctx: Context): List<Entry> {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false) && prefs.getBoolean(KEY_MERGED_LEGACY, false)) {
            return emptyList()
        }
        val enc = File(ctx.applicationContext.filesDir, "vault.enc")
        if (!enc.isFile || enc.length() == 0L) return emptyList()
        return try {
            val plain = VaultCrypto.tryDecrypt(enc.readBytes()) ?: return emptyList()
            parseEntries(String(plain, Charsets.UTF_8))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun persist(ctx: Context, items: MutableList<Entry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("pkg", e.pkg)
                    .put("clone", e.cloneUid)
                    .put("label", e.label)
                    .put("user", e.username)
                    .put("pass", e.password)
                    .put("note", e.note),
            )
        }
        val json = JSONObject().put("v", 2).put("entries", arr).toString()
        val app = ctx.applicationContext
        try {
            val file = vaultFile(app)
            val tmp = File(app.filesDir, "$FILE_JSON.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        } catch (t: Throwable) {
            ToolboxCrashLog.append(app, "ERROR", "vault persist file", t)
        }
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, json)
                .commit()
        } catch (_: Throwable) {
        }
        cache = items
    }

    private fun Map<*, *>.str(k: String): String = this[k]?.toString() ?: ""
}
