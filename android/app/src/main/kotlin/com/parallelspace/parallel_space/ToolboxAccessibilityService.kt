package com.parallelspace.parallel_space

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 只在用户点「填入」时，把用户名和密码写入当前窗口的登录表单。
 * 不记录按键，也不持久化窗口内容。
 *
 * 覆盖：普通 EditText、密码框、WebView 内节点；SET_TEXT 失败时退到焦点 + 粘贴。
 * 虚拟引擎分身跑在宿主进程里，无障碍同样能看到其窗口。
 */
class ToolboxAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        ToolboxController.noteAccessibility(this, enabled = true)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        ToolboxController.noteAccessibility(this, enabled = false)
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
        if (roots.isEmpty()) return false
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
        ok
    } catch (_: Throwable) {
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
    }
}

private val USER_HINTS = listOf(
    "user", "email", "phone", "account", "login", "name", "mobile", "id",
    "用户", "账号", "帐号", "手机", "邮箱", "学号", "工号", "电话", "号码",
)
private val PASS_HINTS = listOf(
    "pass", "pwd", "password", "密码", "口令",
)

private fun fillRoot(
    root: AccessibilityNodeInfo,
    username: String,
    password: String,
    svc: ToolboxAccessibilityService,
): Boolean {
    val edits = ArrayList<AccessibilityNodeInfo>()
    collectEdits(root, edits)
    if (edits.isEmpty()) return false

    val passwordNode = edits.firstOrNull { looksLikePassword(it) }
    val userNode = when {
        username.isEmpty() -> null
        else -> {
            // 优先：hint/id 像账号
            edits.firstOrNull { !looksLikePassword(it) && looksLikeUser(it) }
            // 其次：密码框前面最近的可编辑框
                ?: passwordNode?.let { pwd ->
                    val idx = edits.indexOf(pwd)
                    if (idx > 0) edits.subList(0, idx).asReversed().firstOrNull { !looksLikePassword(it) } else null
                }
                // 再次：任意非密码可编辑框
                ?: edits.firstOrNull { !looksLikePassword(it) }
        }
    }
    val focused = edits.firstOrNull { it.isFocused }

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
            focused != null && focused.isEditable -> {
                if (username.isNotEmpty() && !looksLikePassword(focused)) ok = writeText(focused, username, svc)
                if (password.isNotEmpty() && passwordNode != null) ok = writeText(passwordNode, password, svc) || ok
                else if (password.isNotEmpty() && looksLikePassword(focused)) ok = writeText(focused, password, svc) || ok
            }
            passwordNode != null && password.isNotEmpty() -> ok = writeText(passwordNode, password, svc)
            userNode != null && username.isNotEmpty() -> ok = writeText(userNode, username, svc)
        }
    } finally {
        for (n in edits) {
            if (n !== root) {
                try {
                    n.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }
    return ok
}

private fun collectEdits(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
    if (node == null) return
    val cls = node.className?.toString().orEmpty()
    val isWeb = cls.contains("WebView", true) || cls.contains("chromium", true)
    val edit = node.isEditable ||
        cls.contains("EditText", true) ||
        cls.contains("TextInputEditText", true) ||
        cls.contains("TextField", true) ||
        (cls.contains("AutoComplete", true) && node.isFocusable)
    // WebView 子树里的可输入节点（含 html input 暴露的节点）
    val webInput = isWeb.not() && node.isFocusable && node.isClickable &&
        (looksLikePassword(node) || looksLikeUser(node)) &&
        (node.text?.isNotEmpty() == true || (Build.VERSION.SDK_INT >= 26 && node.hintText != null))
    if (edit || webInput) {
        // 复制一份加入列表，避免父节点递归 recycle 影响
        val copy = AccessibilityNodeInfo.obtain(node)
        if (copy != null) out += copy else out += node
    }
    for (i in 0 until node.childCount) {
        val child = try {
            node.getChild(i)
        } catch (_: Throwable) {
            null
        } ?: continue
        collectEdits(child, out)
        // WebView 也要继续往下挖
        if (isWeb) {
            // already recursing via children
        }
        try {
            if (child !in out) child.recycle()
        } catch (_: Throwable) {
        }
    }
}

private fun meta(n: AccessibilityNodeInfo): String {
    val id = n.viewIdResourceName.orEmpty()
    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
    val desc = n.contentDescription?.toString().orEmpty()
    val text = n.text?.toString().orEmpty()
    return "$id $hint $desc $text".lowercase()
}

private fun looksLikeUser(n: AccessibilityNodeInfo): Boolean {
    val s = meta(n)
    return USER_HINTS.any { s.contains(it) }
}

private fun looksLikePassword(n: AccessibilityNodeInfo): Boolean {
    if (n.isPassword) return true
    val s = meta(n)
    if (PASS_HINTS.any { s.contains(it) }) return true
    // inputType 在部分 ROM / WebView 上不可用，忽略异常
    return false
}

/**
 * 先 ACTION_SET_TEXT；失败则焦点 + 剪贴板 + ACTION_PASTE；最后校验文本是否落地。
 */
private fun writeText(node: AccessibilityNodeInfo, text: String, svc: ToolboxAccessibilityService): Boolean {
    if (text.isEmpty()) return false
    // 尝试聚焦目标
    try {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (_: Throwable) {
    }
    SystemClock.sleep(40)

    val setOk = try {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (_: Throwable) {
        false
    }
    if (setOk && textLanded(node, text)) return true

    // 退路：剪贴板 + 粘贴（不自动清除）
    val pasted = try {
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("平行空间", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        SystemClock.sleep(40)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    } catch (_: Throwable) {
        false
    }
    if (pasted && textLanded(node, text)) return true

    // 密码框往往读不到 text，SET_TEXT / PASTE 返回 true 也算成功
    if (node.isPassword && (setOk || pasted)) return true
    return setOk || pasted
}

private fun textLanded(node: AccessibilityNodeInfo, expected: String): Boolean = try {
    if (node.isPassword) return true // 密码框通常读不到明文
    val cur = node.text?.toString().orEmpty()
    cur == expected || cur.endsWith(expected) || expected.endsWith(cur) && cur.isNotEmpty()
} catch (_: Throwable) {
    false
}
