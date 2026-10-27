package com.parallelspace.parallel_space

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 账号密码本：明文 JSON 存在 SharedPreferences，方便一键填入，不做生物识别门禁。
 * 若本机还有 1.3.4/1.3.5 的 Keystore 加密文件 `vault.enc`，能解开就迁过来。
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

    private const val PREFS = "vault_plain"
    private const val KEY_JSON = "entries_json"
    private const val KEY_MIGRATED = "migrated_enc"

    private val lock = Any()
    private var cache: MutableList<Entry>? = null

    /** 兼容旧调用：明文存储不再需要会话，这里只清内存缓存。 */
    fun lock() {
        synchronized(lock) { cache = null }
    }

    fun list(ctx: Context): List<Entry> = synchronized(lock) {
        load(ctx).toList()
    }

    /** @return "ok" 或错误说明 */
    fun upsert(ctx: Context, raw: Map<*, *>): String = synchronized(lock) {
        val pkg = raw.str("pkg").trim().replace("\n", "")
        if (pkg.isEmpty() || pkg.length > 200) return "请填写应用包名"
        val label = raw.str("label").trim().ifEmpty { "未命名" }.take(40)
        val user = raw.str("user").trim().take(200)
        val pass = raw.str("pass").take(200)
        val note = raw.str("note").trim().take(200)
        if (user.isEmpty() && pass.isEmpty()) return "请至少填写用户名或密码"
        val clone = raw.str("clone").trim().take(220)
        val items = load(ctx)
        if (items.size >= 300 && raw.str("id").isBlank()) return "密码本已满"
        val id = raw.str("id").ifBlank { UUID.randomUUID().toString() }
        val next = Entry(id, pkg, clone, label, user, pass, note)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = next else items.add(next)
        persist(ctx, items)
        "ok"
    }

    fun delete(ctx: Context, id: String): String = synchronized(lock) {
        val items = load(ctx)
        val removed = items.removeAll { it.id == id }
        if (!removed) return "not_found"
        persist(ctx, items)
        "ok"
    }

    fun find(ctx: Context, id: String): Entry? = synchronized(lock) {
        load(ctx).firstOrNull { it.id == id }
    }

    class Locked : IllegalStateException("locked")

    private fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        migrateIfNeeded(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null)
        val items = mutableListOf<Entry>()
        if (!raw.isNullOrBlank()) {
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
        }
        cache = items
        return items
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
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, json)
            .apply()
        cache = items
    }

    /** 尽量把旧版 vault.enc 迁到明文；解不开就跳过，不影响新条目。 */
    private fun migrateIfNeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val enc = File(ctx.filesDir, "vault.enc")
        if (!enc.isFile || enc.length() == 0L) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        try {
            val plain = VaultCrypto.tryDecrypt(enc.readBytes()) ?: run {
                // 需要生物识别才能解，本次解不开；下次启动再试
                return
            }
            val root = JSONObject(String(plain, Charsets.UTF_8))
            val arr = root.optJSONArray("entries") ?: JSONArray()
            val items = mutableListOf<Entry>()
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
            persist(ctx, items)
            enc.delete()
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        } catch (_: Throwable) {
            // 保留 enc，下次再试；不阻断明文密码本
        }
    }

    private fun Map<*, *>.str(k: String): String = this[k]?.toString() ?: ""
}
