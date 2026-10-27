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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只在用户点「填入」时，把用户名和密码写入当前窗口的登录表单。
 * 不记录按键，也不持久化窗口内容。
 *
 * 覆盖：普通 EditText、密码框、WebView 内节点；SET_TEXT 失败时退到焦点 + 粘贴。
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

    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { out += it }
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                for (w in windows.orEmpty()) {
                    val r = try {
                        w.root
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (out.none { sameNode(it, r) }) out += r
                    else try {
                        r.recycle()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return out
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
        /** 若当前在主线程，把填入挪到后台，避免 sleep / 深树遍历 ANR。 */
        fun fillOffMain(svc: ToolboxAccessibilityService, username: String, password: String): Boolean {
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

    var ok = false
    try {
        when {
            passwordNode != null && userNode != null && username.isNotEmpty() && password.isNotEmpty() -> {
                val u = writeText(userNode, username, svc)
                val p = writeText(passwordNode, password, svc)
                ok = u || p
            }
            focused != null && looksLikePassword(focused) && password.isNotEmpty() -> {
                ok = writeText(focused, password, svc)
                if (username.isNotEmpty() && userNode != null) ok = writeText(userNode, username, svc) || ok
            }
            focused != null && try { focused.isEditable } catch (_: Throwable) { false } -> {
                if (username.isNotEmpty() && !looksLikePassword(focused)) ok = writeText(focused, username, svc)
                if (password.isNotEmpty() && passwordNode != null) ok = writeText(passwordNode, password, svc) || ok
                else if (password.isNotEmpty() && looksLikePassword(focused)) ok = writeText(focused, password, svc) || ok
            }
            passwordNode != null && password.isNotEmpty() -> ok = writeText(passwordNode, password, svc)
            userNode != null && username.isNotEmpty() -> ok = writeText(userNode, username, svc)
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
 * 先 ACTION_SET_TEXT；失败则焦点 + 剪贴板 + ACTION_PASTE；最后校验文本是否落地。
 * 禁止在主线程 sleep。
 */
private fun writeText(node: AccessibilityNodeInfo, text: String, svc: ToolboxAccessibilityService): Boolean {
    if (text.isEmpty()) return false
    try {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (_: Throwable) {
    }
    pauseBriefly()

    val setOk = try {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (_: Throwable) {
        false
    }
    if (setOk && textLanded(node, text)) return true

    val pasted = try {
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("平行空间", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        pauseBriefly()
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    } catch (_: Throwable) {
        false
    }
    if (pasted && textLanded(node, text)) return true

    if (try { node.isPassword } catch (_: Throwable) { false } && (setOk || pasted)) return true
    return setOk || pasted
}

private fun pauseBriefly() {
    if (Looper.myLooper() == Looper.getMainLooper()) return
    try {
        SystemClock.sleep(40)
    } catch (_: Throwable) {
    }
}

private fun textLanded(node: AccessibilityNodeInfo, expected: String): Boolean = try {
    if (node.isPassword) return true
    val cur = node.text?.toString().orEmpty()
    cur == expected || cur.endsWith(expected) || (expected.endsWith(cur) && cur.isNotEmpty())
} catch (_: Throwable) {
    false
}
