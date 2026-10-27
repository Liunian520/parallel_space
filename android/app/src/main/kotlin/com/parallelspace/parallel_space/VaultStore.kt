package com.parallelspace.parallel_space

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 认证通过后的短时会话。折叠悬浮窗或点「锁定」会清掉内存里的明文。 */
object VaultSession {
    @Volatile
    private var untilElapsed = 0L

    fun open(seconds: Int = 60) {
        untilElapsed = SystemClock.elapsedRealtime() + seconds * 1000L
    }

    fun valid(): Boolean = SystemClock.elapsedRealtime() < untilElapsed

    fun close() {
        untilElapsed = 0L
    }

    fun touch() {
        if (valid()) open()
    }
}

/**
 * 整份密码本加密后落在应用私有目录。按分身 uid（没有则按包名）分组。
 * 未解锁时不把明文放进内存。
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

    private val lock = Any()
    private var cache: MutableList<Entry>? = null

    fun lock() {
        synchronized(lock) { cache = null }
        VaultSession.close()
    }

    fun list(ctx: Context): List<Entry> = synchronized(lock) {
        requireSession()
        load(ctx).toList()
    }

    /** @return "ok" 或错误说明 */
    fun upsert(ctx: Context, raw: Map<*, *>): String = synchronized(lock) {
        requireSession()
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
        requireSession()
        val items = load(ctx)
        val removed = items.removeAll { it.id == id }
        if (!removed) return "not_found"
        persist(ctx, items)
        "ok"
    }

    fun find(ctx: Context, id: String): Entry? = synchronized(lock) {
        requireSession()
        load(ctx).firstOrNull { it.id == id }
    }

    private fun requireSession() {
        if (!VaultSession.valid()) throw Locked()
        VaultSession.touch()
    }

    class Locked : IllegalStateException("locked")

    private fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.isFile || f.length() == 0L) {
            cache = mutableListOf()
            return cache!!
        }
        val plain = VaultCrypto.decrypt(f.readBytes())
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
        val plain = JSONObject().put("v", 1).put("entries", arr).toString().toByteArray(Charsets.UTF_8)
        val blob = VaultCrypto.encrypt(plain)
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(f)) {
            f.writeBytes(blob)
            tmp.delete()
        }
        cache = items
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "vault.enc")

    private fun Map<*, *>.str(k: String): String = this[k]?.toString() ?: ""
}
