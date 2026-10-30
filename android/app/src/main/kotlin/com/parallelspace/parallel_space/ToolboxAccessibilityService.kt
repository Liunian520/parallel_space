package com.parallelspace.parallel_space

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只在用户点「填入」时，把用户名和密码写入当前窗口的登录表单。
 * 不记录按键，也不持久化窗口内容。
 *
 * 覆盖：普通 EditText、密码框、WebView 内节点；先清空再 SET_TEXT，失败才全选+粘贴；1s 防抖。
 * 虚拟引擎分身跑在宿主进程里，无障碍同样能看到其窗口。
 *
 * 1.3.9：树遍历限深/限节点、异常全吞并写日志；fill 可在后台线程调用，
 * sleep 绝不在主线程；节点 recycle 更保守，避免 stale / double-recycle 崩服务。
 */
class ToolboxAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        ToolboxCrashLog.installHandler(this, "a11y")
        ToolboxCrashLog.append(this, "INFO", "a11y connected")
        ToolboxController.noteAccessibility(this, enabled = true)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        ToolboxController.noteAccessibility(this, enabled = false)
        ToolboxCrashLog.append(this, "INFO", "a11y unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 故意不读取、不保存事件内容。填入时再临时取当前窗口。
    }

    override fun onInterrupt() {}

    // 1.3.27：被系统回收时尽量自救；拿到 WRITE_SECURE_SETTINGS 时会自动重新开启
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        try { A11yKeeper.ensure(this, "taskRemoved") } catch (_: Throwable) {}
    }

    /**
     * @return true 表示至少成功写入了一个字段并校验通过。
     */
    fun fill(username: String, password: String): Boolean = try {
        val roots = collectRoots()
        if (roots.isEmpty()) {
            ToolboxCrashLog.append(this, "WARN", "fill: no roots")
            return false
        }
        var ok = false
        try {
            for (root in roots) {
                if (fillRoot(root, username, password, this)) {
                    ok = true
                    break
                }
            }
        } finally {
            for (r in roots) {
                try {
                    r.recycle()
                } catch (_: Throwable) {
                }
            }
        }
        ToolboxCrashLog.append(this, "INFO", "fill result=$ok")
        ok
    } catch (t: Throwable) {
        ToolboxCrashLog.append(this, "ERROR", "fill crashed", t)
        false
    }

    /**
     * 优先取 TYPE_APPLICATION（分身登录页），跳过输入法 / 无障碍覆盖层 / 系统层。
     * 展开的悬浮窗常抢到 rootInActiveWindow，必须排到后面或丢弃无 EditText 的层。
     */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val app = ArrayList<AccessibilityNodeInfo>()
        val other = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                for (w in windows.orEmpty()) {
                    val r = try {
                        w.root
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val type = try {
                        w.type
                    } catch (_: Throwable) {
                        -1
                    }
                    // 输入法 / 无障碍层几乎不可能是登录表单
                    if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    ) {
                        try {
                            r.recycle()
                        } catch (_: Throwable) {
                        }
                        continue
                    }
                    // 跳过本应用的系统层悬浮窗（TYPE_SYSTEM），避免填到工具箱自己
                    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                        val rPkg = try {
                            r.packageName?.toString()
                        } catch (_: Throwable) {
                            null
                        }
                        if (rPkg != null && rPkg == packageName) {
                            try {
                                r.recycle()
                            } catch (_: Throwable) {
                            }
                            continue
                        }
                    }
                    val bucket = if (type == AccessibilityWindowInfo.TYPE_APPLICATION) app else other
                    if (bucket.none { sameNode(it, r) } && app.none { sameNode(it, r) } && other.none { sameNode(it, r) }) {
                        bucket += r
                    } else {
                        try {
                            r.recycle()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                ToolboxCrashLog.append(this, "WARN", "collectRoots windows", t)
            }
        }
        // rootInActiveWindow 兜底（可能是悬浮窗，放最后）
        try {
            val active = rootInActiveWindow
            if (active != null) {
                val known = app + other
                if (known.none { sameNode(it, active) }) other += active
                else try {
                    active.recycle()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        ToolboxCrashLog.append(this, "INFO", "collectRoots app=${app.size} other=${other.size}")
        return app + other
    }

    private fun sameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean = try {
        a == b || (a.windowId == b.windowId && a.viewIdResourceName == b.viewIdResourceName &&
            a.className == b.className && a.text == b.text)
    } catch (_: Throwable) {
        false
    }

    companion object {
        @Volatile
        var instance: ToolboxAccessibilityService? = null

        private val fillExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "toolbox-a11y-fill").apply { isDaemon = true }
        }
        @Volatile private var lastFillAt = 0L
        private const val FILL_DEBOUNCE_MS = 1000L

        /**
         * 若当前在主线程，把填入挪到后台，避免 sleep / 深树遍历 ANR。
         * 1 秒内重复调用直接丢弃，防止双击 / 面板重建触发两次叠字。
         */
        fun fillOffMain(svc: ToolboxAccessibilityService, username: String, password: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            val prev = lastFillAt
            if (now - prev < FILL_DEBOUNCE_MS) {
                ToolboxCrashLog.append(svc, "INFO", "fill debounce skip gap=${now - prev}ms")
                return true // 当作成功，避免上层再走「复制」退路把剪贴板搞乱
            }
            lastFillAt = now
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return svc.fill(username, password)
            }
            val box = BooleanArray(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            fillExecutor.execute {
                try {
                    box[0] = svc.fill(username, password)
                } catch (_: Throwable) {
                    box[0] = false
                } finally {
                    latch.countDown()
                }
            }
            return try {
                latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
                box[0]
            } catch (_: Throwable) {
                false
            }
        }
    }
}

private val USER_HINTS = listOf(
    "user", "email", "phone", "account", "login", "name", "mobile", "id",
    "用户", "账号", "帐号", "手机", "邮箱", "学号", "工号", "电话", "号码",
)
private val PASS_HINTS = listOf(
    "pass", "pwd", "password", "密码", "口令",
)

private const val MAX_DEPTH = 48
private const val MAX_NODES = 250

private fun fillRoot(
    root: AccessibilityNodeInfo,
    username: String,
    password: String,
    svc: ToolboxAccessibilityService,
): Boolean {
    val edits = ArrayList<AccessibilityNodeInfo>()
    val counter = AtomicInteger(0)
    try {
        collectEdits(root, edits, 0, counter)
    } catch (t: Throwable) {
        ToolboxCrashLog.append(svc, "ERROR", "collectEdits", t)
    }
    if (edits.isEmpty()) return false

    val passwordNode = edits.firstOrNull { looksLikePassword(it) }
    val userNode = when {
        username.isEmpty() -> null
        else -> {
            edits.firstOrNull { !looksLikePassword(it) && looksLikeUser(it) }
                ?: passwordNode?.let { pwd ->
                    val idx = edits.indexOf(pwd)
                    if (idx > 0) edits.subList(0, idx).asReversed().firstOrNull { !looksLikePassword(it) } else null
                }
                ?: edits.firstOrNull { !looksLikePassword(it) }
        }
    }
    val focused = edits.firstOrNull { try { it.isFocused } catch (_: Throwable) { false } }

    val written = HashSet<String>()
    fun writeOnce(n: AccessibilityNodeInfo?, value: String): Boolean {
        if (n == null || value.isEmpty()) return false
        val key = nodeKey(n)
        if (!written.add(key)) {
            ToolboxCrashLog.append(svc, "INFO", "write skip duplicate-node key=$key")
            return false
        }
        return writeText(n, value, svc)
    }
    var ok = false
    try {
        ToolboxCrashLog.append(
            svc, "INFO",
            "fillRoot edits=${edits.size} user=${userNode?.let(::fieldLabel)} pass=${passwordNode?.let(::fieldLabel)} focused=${focused?.let(::fieldLabel)}",
        )
        when {
            passwordNode != null && userNode != null && username.isNotEmpty() && password.isNotEmpty() -> {
                val u = writeOnce(userNode, username)
                val p = writeOnce(passwordNode, password)
                ok = u || p
            }
            focused != null && looksLikePassword(focused) && password.isNotEmpty() -> {
                ok = writeOnce(focused, password)
                if (username.isNotEmpty()) ok = writeOnce(userNode, username) || ok
            }
            focused != null && try { focused.isEditable } catch (_: Throwable) { false } -> {
                if (username.isNotEmpty() && !looksLikePassword(focused)) ok = writeOnce(focused, username)
                if (password.isNotEmpty() && passwordNode != null) ok = writeOnce(passwordNode, password) || ok
                else if (password.isNotEmpty() && looksLikePassword(focused)) ok = writeOnce(focused, password) || ok
            }
            passwordNode != null && password.isNotEmpty() -> ok = writeOnce(passwordNode, password)
            userNode != null && username.isNotEmpty() -> ok = writeOnce(userNode, username)
        }
    } finally {
        for (n in edits) {
            try {
                n.recycle()
            } catch (_: Throwable) {
            }
        }
    }
    return ok
}

private fun collectEdits(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>,
    depth: Int,
    counter: AtomicInteger,
) {
    if (node == null) return
    if (depth > MAX_DEPTH) return
    if (counter.get() >= MAX_NODES) return
    counter.incrementAndGet()
    try {
        val cls = node.className?.toString().orEmpty()
        val isWeb = cls.contains("WebView", true) || cls.contains("chromium", true)
        val edit = try {
            node.isEditable ||
                cls.contains("EditText", true) ||
                cls.contains("TextInputEditText", true) ||
                cls.contains("TextField", true) ||
                (cls.contains("AutoComplete", true) && node.isFocusable)
        } catch (_: Throwable) {
            false
        }
        val webInput = try {
            !isWeb && node.isFocusable && node.isClickable &&
                (looksLikePassword(node) || looksLikeUser(node)) &&
                (node.text?.isNotEmpty() == true || (Build.VERSION.SDK_INT >= 26 && node.hintText != null))
        } catch (_: Throwable) {
            false
        }
        if (edit || webInput) {
            val copy = try {
                AccessibilityNodeInfo.obtain(node)
            } catch (_: Throwable) {
                null
            }
            if (copy != null) out += copy
        }
        val childCount = try {
            node.childCount
        } catch (_: Throwable) {
            0
        }
        for (i in 0 until childCount) {
            if (counter.get() >= MAX_NODES) break
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                collectEdits(child, out, depth + 1, counter)
            } finally {
                try {
                    child.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    } catch (_: Throwable) {
    }
}

private fun meta(n: AccessibilityNodeInfo): String = try {
    val id = n.viewIdResourceName.orEmpty()
    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
    val desc = n.contentDescription?.toString().orEmpty()
    val text = n.text?.toString().orEmpty()
    "$id $hint $desc $text".lowercase()
} catch (_: Throwable) {
    ""
}

private fun looksLikeUser(n: AccessibilityNodeInfo): Boolean {
    val s = meta(n)
    return USER_HINTS.any { s.contains(it) }
}

private fun looksLikePassword(n: AccessibilityNodeInfo): Boolean {
    try {
        if (n.isPassword) return true
    } catch (_: Throwable) {
    }
    val s = meta(n)
    return PASS_HINTS.any { s.contains(it) }
}

/**
 * 写入策略（针对 HyperOS / 部分 App 的「SET_TEXT 追加」与「node.text 未刷新」）：
 * 1. 已是目标值 → 跳过（避免二次写入叠字）
 * 2. 先清空（SET_TEXT ""），再 SET_TEXT 完整值一次
 * 3. 刷新节点后做**精确**相等校验（禁止 endsWith 宽松匹配，那会把叠字当成成功）
 * 4. SET_TEXT 失败才：焦点 → 全选 → 粘贴一次
 * 禁止在主线程 sleep。
 */
private fun writeText(node: AccessibilityNodeInfo, text: String, svc: ToolboxAccessibilityService): Boolean {
    if (text.isEmpty()) return false
    val hint = fieldLabel(node)
    val before = currentText(node)
    val beforeLen = before.length
    ToolboxCrashLog.append(
        svc, "INFO",
        "write begin hint=$hint cls=${safeClass(node)} beforeLen=$beforeLen wantLen=${text.length} pwd=${safeIsPassword(node)}",
    )
    // 已正确：不要再写，否则 HyperOS 上 SET_TEXT 会追加成两份
    if (!safeIsPassword(node) && before == text) {
        ToolboxCrashLog.append(svc, "INFO", "write skip already-correct hint=$hint")
        return true
    }

    try {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (_: Throwable) {
    }
    pauseBriefly()

    // 先清空，避免 SET_TEXT 在部分 ROM 上变成 append
    clearField(node)
    pauseBriefly()

    val setOk = setTextAction(node, text)
    pauseBriefly()
    refresh(node)
    val afterSet = currentText(node)
    ToolboxCrashLog.append(
        svc, "INFO",
        "write SET_TEXT ok=$setOk afterLen=${afterSet.length} exact=${afterSet == text} hint=$hint",
    )
    if (exactOrPasswordOk(node, text, afterSet)) {
        ToolboxCrashLog.append(svc, "INFO", "write done method=SET_TEXT hint=$hint afterLen=${afterSet.length}")
        return true
    }

    // SET_TEXT 未精确落地：全选后粘贴一次（禁止不清空就粘贴）
    val pasted = pasteOnce(node, text, svc)
    pauseBriefly()
    refresh(node)
    val afterPaste = currentText(node)
    ToolboxCrashLog.append(
        svc, "INFO",
        "write PASTE ok=$pasted afterLen=${afterPaste.length} exact=${afterPaste == text} hint=$hint",
    )
    if (exactOrPasswordOk(node, text, afterPaste)) {
        ToolboxCrashLog.append(svc, "INFO", "write done method=PASTE hint=$hint afterLen=${afterPaste.length}")
        return true
    }

    // 密码框往往读不到 text；SET_TEXT 或 PASTE 任一返回 true 就算成功，但我们已经先清空，不会叠字
    if (safeIsPassword(node) && (setOk || pasted)) {
        ToolboxCrashLog.append(svc, "INFO", "write done method=password-trust set=$setOk paste=$pasted hint=$hint")
        return true
    }
    ToolboxCrashLog.append(svc, "WARN", "write failed hint=$hint beforeLen=$beforeLen afterLen=${afterPaste.length}")
    return false
}

private fun setTextAction(node: AccessibilityNodeInfo, text: String): Boolean = try {
    val args = Bundle()
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
} catch (_: Throwable) {
    false
}

private fun clearField(node: AccessibilityNodeInfo) {
    try {
        setTextAction(node, "")
    } catch (_: Throwable) {
    }
    try {
        val len = currentText(node).length
        if (len > 0) {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            setTextAction(node, "")
        }
    } catch (_: Throwable) {
    }
}

private fun pasteOnce(node: AccessibilityNodeInfo, text: String, svc: ToolboxAccessibilityService): Boolean = try {
    // 再清一次 + 全选，确保粘贴是替换而不是追加
    clearField(node)
    pauseBriefly()
    val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Alter Ego", text))
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    pauseBriefly()
    val len = currentText(node).length
    if (len > 0) {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len.coerceAtLeast(1))
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }
    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
} catch (_: Throwable) {
    false
}

private fun exactOrPasswordOk(node: AccessibilityNodeInfo, expected: String, cur: String): Boolean {
    if (safeIsPassword(node)) return false // 密码交给调用方用 setOk/pasted 判断，这里不做「宽松成功」
    return cur == expected
}

private fun currentText(node: AccessibilityNodeInfo): String = try {
    node.text?.toString().orEmpty()
} catch (_: Throwable) {
    ""
}

private fun safeIsPassword(node: AccessibilityNodeInfo): Boolean = try {
    node.isPassword
} catch (_: Throwable) {
    false
}

private fun safeClass(node: AccessibilityNodeInfo): String = try {
    node.className?.toString().orEmpty().substringAfterLast('.')
} catch (_: Throwable) {
    "?"
}

private fun fieldLabel(node: AccessibilityNodeInfo): String = try {
    val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
    val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
    val desc = node.contentDescription?.toString().orEmpty()
    listOf(id, hint, desc).firstOrNull { it.isNotBlank() } ?: safeClass(node)
} catch (_: Throwable) {
    "?"
}

private fun refresh(node: AccessibilityNodeInfo) {
    try {
        node.refresh()
    } catch (_: Throwable) {
    }
}

private fun pauseBriefly() {
    if (Looper.myLooper() == Looper.getMainLooper()) return
    try {
        SystemClock.sleep(50)
    } catch (_: Throwable) {
    }
}

/** 节点身份：同一填入过程中同一字段只写一次。 */
private fun nodeKey(n: AccessibilityNodeInfo): String = try {
    "${n.windowId}|${n.viewIdResourceName}|${n.className}|${n.hashCode()}"
} catch (_: Throwable) {
    n.hashCode().toString()
}
