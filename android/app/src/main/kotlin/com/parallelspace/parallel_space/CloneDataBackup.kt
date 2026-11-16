package com.parallelspace.parallel_space

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 把虚拟引擎里某个（或全部）分身的应用数据打成 .aebak（zip），并按决策恢复。
 *
 * 路径全部走 [BEnvironment]，不手写目录：
 * - CE：`getDataDir(pkg, userId)` → `{filesParent}/blackbox/data/user/{userId}/{pkg}`
 * - DE：`getDeDataDir`
 * - 外置：`getExternalDataDir` / `getExternalObbDir`
 * - APK：`getBaseApkDir` / `getAppDir`
 */
object CloneDataBackup {
    private const val TAG = "CloneDataBackup"
    private val main = Handler(Looper.getMainLooper())
    private val bufSize = 64 * 1024

    @Volatile
    var progressListener: ((Map<String, Any?>) -> Unit)? = null

    data class Item(
        val packageName: String,
        val userId: Int,
        val customLabel: String?,
        val appName: String,
    )

    fun pack(ctx: Context, items: List<Item>, outFile: File, appVersion: String): Map<String, Any?> {
        if (!VirtualEngine.available) {
            return mapOf("r" to "engine_unavailable", "detail" to (VirtualEngine.error ?: "引擎未就绪"))
        }
        if (items.isEmpty()) return mapOf("r" to "empty", "detail" to "没有可备份的分身")
        outFile.parentFile?.mkdirs()
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        val warnings = ArrayList<String>()
        val cloneMetas = ArrayList<JSONObject>()
        try {
            emit("preparing", 0, items.size, null, "正在准备…", 0L, 0L)
            for ((i, it) in items.withIndex()) {
                emit("stopping", i + 1, items.size, it.packageName, "正在停止 ${display(it)}", 0L, 0L)
                stopQuiet(it.packageName, it.userId)
            }
            sleepQuiet(300)

            var packedBytes = 0L
            var lastEmit = 0L
            fun onBytes(n: Long) {
                packedBytes += n
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 250) {
                    lastEmit = now
                    emit("packing", 0, items.size, null, "正在写入备份…", packedBytes, 0L)
                }
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), bufSize)).use { zip ->
                zip.setLevel(6)
                for ((i, it) in items.withIndex()) {
                    emit("packing", i + 1, items.size, it.packageName, "正在打包 ${display(it)}", packedBytes, 0L)
                    val prefix = "clones/$i/"
                    val meta = JSONObject()
                        .put("packageName", it.packageName)
                        .put("userId", it.userId)
                        .put("customLabel", it.customLabel ?: "")
                        .put("appName", it.appName)
                    val dirs = JSONArray()
                    val skipped = JSONArray().put("code_cache").put("lib")

                    val apkFiles = collectApks(ctx, it.packageName)
                    var hasApk = false
                    for (f in apkFiles) {
                        val name = if (f.name.equals("base.apk", true) || isBaseApk(it.packageName, f)) {
                            "base.apk"
                        } else {
                            f.name
                        }
                        packFile(zip, f, prefix + "apk/" + name, ::onBytes)
                        hasApk = true
                    }
                    if (!hasApk) {
                        warnings += "${display(it)} 未找到安装包，已备份数据。换到新手机恢复时，需要本机已安装该应用。"
                    }
                    meta.put("hasApk", hasApk)

                    fun packTree(root: File?, dirName: String) {
                        if (root == null || !root.exists()) return
                        var any = false
                        walk(root, "") { rel, file ->
                            if (file.isDirectory) return@walk
                            if (CloneDataPolicy.shouldSkipDataEntry(rel)) return@walk
                            packFile(zip, file, prefix + dirName + "/" + rel, ::onBytes)
                            any = true
                        }
                        if (any) dirs.put(dirName)
                    }
                    packTree(BEnvironment.getDataDir(it.packageName, it.userId), "data")
                    packTree(BEnvironment.getDeDataDir(it.packageName, it.userId), "data_de")
                    packTree(runCatching { BEnvironment.getExternalDataDir(it.packageName, it.userId) }.getOrNull(), "external_data")
                    packTree(BEnvironment.getExternalObbDir(it.packageName, it.userId), "external_obb")

                    meta.put("dirs", dirs)
                    meta.put("skipped", skipped)
                    val metaBytes = meta.toString().toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry(prefix + "meta.json"))
                    zip.write(metaBytes)
                    zip.closeEntry()
                    cloneMetas += meta
                }
                val manifest = JSONObject()
                    .put("format", CloneDataPolicy.FORMAT)
                    .put("version", CloneDataPolicy.VERSION)
                    .put("exportedAt", isoNow())
                    .put("appVersion", appVersion)
                    .put("clones", JSONArray().also { arr -> cloneMetas.forEach { arr.put(it) } })
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (outFile.exists()) outFile.delete()
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
            emit("done", items.size, items.size, null, "打包完成", outFile.length(), outFile.length())
            return mapOf(
                "r" to "ok",
                "path" to outFile.absolutePath,
                "size" to outFile.length(),
                "fileName" to outFile.name,
                "warnings" to ArrayList(warnings),
                "count" to items.size,
            )
        } catch (t: Throwable) {
            tmp.delete()
            Log.e(TAG, "pack failed", t)
            return mapOf("r" to "error", "detail" to (t.message ?: t.javaClass.simpleName))
        }
    }

    fun preview(file: File): Map<String, Any?> {
        val manifest = readManifest(file)
            ?: return mapOf("r" to "invalid", "detail" to "无法读取备份文件")
        val check = CloneDataPolicy.checkFormat(manifest.optString("format"), manifest.optInt("version", -1))
        when (check) {
            CloneDataPolicy.CHECK_NEED_UPGRADE -> return mapOf(
                "r" to "need_upgrade",
                "version" to manifest.optInt("version"),
                "detail" to "这份备份来自更新版本的 Alter Ego，请先升级应用再恢复",
            )
            CloneDataPolicy.CHECK_OK -> {}
            else -> return mapOf("r" to "invalid", "detail" to "这不是有效的分身数据备份")
        }
        val clones = manifest.optJSONArray("clones") ?: JSONArray()
        val installed = installedKeys()
        val list = ArrayList<Map<String, Any?>>()
        for (i in 0 until clones.length()) {
            val c = clones.getJSONObject(i)
            val pkg = c.optString("packageName")
            val uid = c.optInt("userId", 0)
            list += mapOf(
                "packageName" to pkg,
                "userId" to uid,
                "customLabel" to c.optString("customLabel"),
                "appName" to c.optString("appName"),
                "hasApk" to c.optBoolean("hasApk"),
                "exists" to installed.contains(CloneDataPolicy.CloneRef.key(pkg, uid)),
            )
        }
        return mapOf(
            "r" to "ok",
            "exportedAt" to manifest.optString("exportedAt"),
            "appVersion" to manifest.optString("appVersion"),
            "version" to manifest.optInt("version"),
            "clones" to list,
            "count" to list.size,
            "anyExists" to list.any { it["exists"] == true },
            "allExist" to (list.isNotEmpty() && list.all { it["exists"] == true }),
        )
    }

    fun restore(
        ctx: Context,
        file: File,
        mode: String,
        existingLabels: List<String>,
    ): Map<String, Any?> {
        if (!VirtualEngine.available) {
            return mapOf("r" to "engine_unavailable", "detail" to (VirtualEngine.error ?: "引擎未就绪"))
        }
        val preview = preview(file)
        if (preview["r"] != "ok") return preview
        @Suppress("UNCHECKED_CAST")
        val rawClones = preview["clones"] as? List<Map<String, Any?>> ?: emptyList()
        if (rawClones.isEmpty()) return mapOf("r" to "empty", "detail" to "备份里没有分身")
        val refs = rawClones.map {
            CloneDataPolicy.CloneRef(
                it["packageName"] as? String ?: "",
                (it["userId"] as? Number)?.toInt() ?: 0,
                it["customLabel"] as? String,
                it["appName"] as? String ?: "",
                it["hasApk"] == true,
            )
        }
        val plan = CloneDataPolicy.planRestore(refs, mode, installedKeys(), existingLabels)
        val work = File(ctx.cacheDir, "clone_data_restore")
        deleteRecursivelySafe(work)
        work.mkdirs()
        val results = ArrayList<Map<String, Any?>>()
        var ok = 0
        var fail = 0
        cleanupBackupCache(ctx, listOf(file))
        try {
            emit("extracting", 0, plan.size, null, "正在解开备份…", 0L, file.length())
            unzipSafe(file, work)
            for ((i, p) in plan.withIndex()) {
                val name = refs.getOrNull(p.index)?.appName ?: p.packageName
                emit("restoring", i + 1, plan.size, p.packageName, "正在恢复 $name", 0L, 0L)
                if (p.action == CloneDataPolicy.ACTION_ERROR) {
                    fail++
                    results += mapOf(
                        "r" to "error",
                        "packageName" to p.packageName,
                        "sourceUserId" to p.sourceUserId,
                        "targetUserId" to p.targetUserId,
                        "action" to p.action,
                        "label" to p.label,
                        "appName" to name,
                        "detail" to (p.error ?: "无法恢复"),
                    )
                    continue
                }
                val cloneDir = File(work, "clones/${p.index}")
                val one = try {
                    restoreOne(ctx, p, refs.getOrNull(p.index), cloneDir)
                } catch (t: Throwable) {
                    Log.e(TAG, "restore ${p.packageName}#${p.targetUserId}", t)
                    mapOf(
                        "r" to "error",
                        "packageName" to p.packageName,
                        "sourceUserId" to p.sourceUserId,
                        "targetUserId" to p.targetUserId,
                        "action" to p.action,
                        "label" to p.label,
                        "appName" to name,
                        "detail" to (t.message ?: t.javaClass.simpleName),
                    )
                }
                results += one
                if (one["r"] == "ok") ok++ else fail++
            }
        } finally {
            deleteRecursivelySafe(work)
        }
        emit("done", plan.size, plan.size, null, "恢复完成", 0L, 0L)
        val r = when {
            fail == 0 && ok > 0 -> "ok"
            ok == 0 -> "error"
            else -> "partial"
        }
        if (r == "ok" || r == "partial") {
            deleteIfInBackupCache(ctx, file)
        }
        return mapOf("r" to r, "ok" to ok, "fail" to fail, "items" to results)
    }

    private fun restoreOne(
        ctx: Context,
        p: CloneDataPolicy.PlanItem,
        ref: CloneDataPolicy.CloneRef?,
        cloneDir: File,
    ): Map<String, Any?> {
        val pkg = p.packageName
        val target = p.targetUserId
        val appName = ref?.appName ?: pkg
        fun result(code: String, detail: String? = null): Map<String, Any?> = buildMap {
            put("r", code)
            put("packageName", pkg)
            put("sourceUserId", p.sourceUserId)
            put("targetUserId", target)
            put("action", p.action)
            put("label", p.label)
            put("appName", appName)
            if (detail != null) put("detail", detail)
        }

        if (p.action == CloneDataPolicy.ACTION_OVERWRITE && VirtualEngine.isCloned(pkg, target)) {
            stopQuiet(pkg, target)
            sleepQuiet(200)
        }

        if (p.needsApk || !VirtualEngine.isCloned(pkg, target)) {
            val apk = File(cloneDir, "apk/base.apk")
            val inst = when {
                apk.isFile -> VirtualEngine.installFromFile(apk, target)
                hostHas(ctx, pkg) -> VirtualEngine.clone(pkg, target)
                else -> return result(
                    "no_apk",
                    "备份里没有「$appName」的安装包，本机也没有安装该应用，无法恢复。请先在系统中安装后再试。",
                )
            }
            if (inst["r"] != "ok") {
                return result(inst["r"] as? String ?: "error", inst["detail"] as? String ?: "安装分身失败")
            }
        }

        stopQuiet(pkg, target)
        sleepQuiet(200)

        val dataDir = BEnvironment.getDataDir(pkg, target)
        prepareDestTree(dataDir, "data")
        val srcData = File(cloneDir, "data")
        if (srcData.isDirectory) copyTree(srcData, dataDir)

        val deDir = BEnvironment.getDeDataDir(pkg, target)
        prepareDestTree(deDir, "data_de")
        val srcDe = File(cloneDir, "data_de")
        if (srcDe.isDirectory) copyTree(srcDe, deDir)
        if (deDir.exists()) fixPerms(deDir)

        val ext = runCatching { BEnvironment.getExternalDataDir(pkg, target) }.getOrNull()
        prepareDestTree(ext, "external_data")
        val srcExt = File(cloneDir, "external_data")
        if (ext != null && srcExt.isDirectory) copyTree(srcExt, ext)

        val obb = runCatching { BEnvironment.getExternalObbDir(pkg, target) }.getOrNull()
        prepareDestTree(obb, "external_obb")
        val srcObb = File(cloneDir, "external_obb")
        if (obb != null && srcObb.isDirectory) copyTree(srcObb, obb)

        fixPerms(dataDir)

        val appDir = BEnvironment.getAppDir(pkg)
        File(cloneDir, "apk").listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".apk", true) && !f.name.equals("base.apk", true)) {
                appDir.mkdirs()
                f.copyTo(File(appDir, f.name), overwrite = true)
            }
        }
        return result("ok")
    }

    private fun collectApks(ctx: Context, pkg: String): List<File> {
        val out = ArrayList<File>()
        val base = runCatching { BEnvironment.getBaseApkDir(pkg) }.getOrNull()
        if (base != null && base.isFile) out += base
        val appDir = runCatching { BEnvironment.getAppDir(pkg) }.getOrNull()
        appDir?.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".apk", true) && (base == null || !sameFile(f, base))) {
                out += f
            }
        }
        if (out.isEmpty()) {
            hostApk(ctx, pkg)?.let { out += it }
        }
        return out
    }

    private fun isBaseApk(pkg: String, f: File): Boolean {
        val base = runCatching { BEnvironment.getBaseApkDir(pkg) }.getOrNull() ?: return false
        return sameFile(f, base)
    }

    private fun sameFile(a: File, b: File): Boolean = try {
        a.canonicalFile == b.canonicalFile
    } catch (_: Throwable) {
        a.absolutePath == b.absolutePath
    }

    private fun hostApk(ctx: Context, pkg: String): File? = try {
        val src = ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
        if (src.isNullOrEmpty()) null else File(src).takeIf { it.isFile }
    } catch (_: Exception) {
        null
    }

    private fun hostHas(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun installedKeys(): Set<String> {
        val s = LinkedHashSet<String>()
        if (!VirtualEngine.available) return s
        for (m in VirtualEngine.listCloned()) {
            val pkg = m["pkg"] as? String ?: continue
            val uid = (m["userId"] as? Number)?.toInt() ?: 0
            s.add(CloneDataPolicy.CloneRef.key(pkg, uid))
        }
        return s
    }

    private fun readManifest(file: File): JSONObject? {
        if (!file.isFile) return null
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(file), bufSize)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = CloneDataPolicy.normalizePath(e.name)
                    if (name == "manifest.json") {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        zis.closeEntry()
                        return JSONObject(text)
                    }
                    zis.closeEntry()
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "readManifest", t)
            null
        }
    }

    private fun unzipSafe(zipFile: File, dest: File) {
        val destCanon = dest.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), bufSize)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val raw = e.name.replace('\\', '/')
                if (!CloneDataPolicy.isSafeZipEntry(raw)) {
                    zis.closeEntry()
                    continue
                }
                val n = CloneDataPolicy.normalizePath(raw)
                if (n.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val out = File(dest, n)
                val canon = try {
                    out.canonicalFile
                } catch (_: Throwable) {
                    zis.closeEntry()
                    continue
                }
                val root = destCanon.path
                if (canon.path != root && !canon.path.startsWith(root + File.separator)) {
                    zis.closeEntry()
                    continue
                }
                if (e.isDirectory || raw.endsWith("/")) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { os -> zis.copyTo(os, bufSize) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun packFile(zip: ZipOutputStream, file: File, entryName: String, onBytes: (Long) -> Unit) {
        val name = entryName.replace('\\', '/')
        if (!CloneDataPolicy.isSafeZipEntry(name)) return
        if (!file.isFile) return
        val entry = ZipEntry(name)
        entry.time = file.lastModified()
        zip.putNextEntry(entry)
        FileInputStream(file).use { input ->
            val buf = ByteArray(bufSize)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zip.write(buf, 0, n)
                onBytes(n.toLong())
            }
        }
        zip.closeEntry()
    }

    private fun walk(root: File, rel: String, visitor: (String, File) -> Unit) {
        val list = root.listFiles() ?: return
        for (f in list) {
            val name = f.name
            if (name == "." || name == "..") continue
            if (isSymlink(f)) continue
            val childRel = if (rel.isEmpty()) name else "$rel/$name"
            visitor(childRel, f)
            if (f.isDirectory && !CloneDataPolicy.shouldSkipDataEntry(childRel)) {
                walk(f, childRel, visitor)
            }
        }
    }

    private fun copyTree(src: File, dest: File) {
        walk(src, "") { rel, file ->
            if (CloneDataPolicy.shouldSkipDataEntry(rel)) return@walk
            val target = File(dest, rel)
            val destRoot = dest.canonicalFile.path
            val canon = try {
                target.canonicalFile.path
            } catch (_: Throwable) {
                return@walk
            }
            if (canon != destRoot && !canon.startsWith(destRoot + File.separator)) return@walk
            if (file.isDirectory) {
                target.mkdirs()
            } else if (file.isFile) {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private fun prepareDestTree(dest: File?, treeName: String) {
        if (dest == null) return
        val preserve = CloneDataPolicy.destWipePreserve(treeName) ?: return
        CloneDataPolicy.ensureWiped(dest, preserve)
    }

    fun backupCacheDir(ctx: Context): File = File(ctx.cacheDir, "backup")

    fun cleanupBackupCache(ctx: Context, keep: Collection<File> = emptyList()) {
        CloneDataPolicy.cleanupBackupCacheDir(backupCacheDir(ctx), keep)
    }

    fun deleteIfInBackupCache(ctx: Context, file: File): Boolean =
        CloneDataPolicy.deleteIfInBackupCache(backupCacheDir(ctx), file)

    private fun deleteRecursivelySafe(f: File) {
        if (isSymlink(f)) {
            f.delete()
            return
        }
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursivelySafe(it) }
        }
        f.delete()
    }

    private fun fixPerms(root: File) {
        if (!root.exists()) return
        walk(root, "") { _, file ->
            try {
                file.setReadable(true, true)
                file.setWritable(true, true)
                if (file.isDirectory) file.setExecutable(true, true)
            } catch (_: Throwable) {
            }
        }
        try {
            root.setReadable(true, true)
            root.setWritable(true, true)
            root.setExecutable(true, true)
        } catch (_: Throwable) {
        }
    }

    private fun isSymlink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        try {
            f.absoluteFile.canonicalPath != f.absoluteFile.path
        } catch (_: Throwable) {
            false
        }
    }

    private fun stopQuiet(pkg: String, userId: Int) {
        try {
            VirtualEngine.stop(pkg, userId)
        } catch (t: Throwable) {
            Log.w(TAG, "stop $pkg#$userId", t)
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun display(it: Item): String {
        val n = it.customLabel?.trim().orEmpty()
        return if (n.isNotEmpty()) n else it.appName.ifEmpty { it.packageName }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun emit(stage: String, current: Int, total: Int, pkg: String?, message: String, bytes: Long, totalBytes: Long) {
        val ev: Map<String, Any?> = mapOf(
            "stage" to stage,
            "current" to current,
            "total" to total,
            "packageName" to (pkg ?: ""),
            "message" to message,
            "bytes" to bytes,
            "totalBytes" to totalBytes,
        )
        val sink = progressListener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                sink(ev)
            } catch (_: Throwable) {
            }
        } else {
            main.post {
                try {
                    sink(ev)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
