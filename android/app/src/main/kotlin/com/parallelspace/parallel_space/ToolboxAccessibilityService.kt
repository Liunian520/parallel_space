package com.parallelspace.parallel_space

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 只在用户点「填入」时，把用户名和密码写入当前焦点或识别到的密码框。
 * 不记录按键，也不保存窗口内容。
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

    fun fill(username: String, password: String): Boolean = try {
        val root = rootInActiveWindow ?: windows.firstOrNull { it.isActive }?.root ?: return false
        try {
            fillRoot(root, username, password)
        } finally {
            root.recycle()
        }
    } catch (_: Throwable) {
        false
    }

    companion object {
        @Volatile
        var instance: ToolboxAccessibilityService? = null
    }
}

private fun fillRoot(root: AccessibilityNodeInfo, username: String, password: String): Boolean {
    val edits = ArrayList<AccessibilityNodeInfo>()
    collectEdits(root, edits)
    if (edits.isEmpty()) return false
    val passwordNode = edits.firstOrNull { it.isPassword }
    val userNode = edits.firstOrNull { !it.isPassword && looksLikeUser(it) }
        ?: edits.firstOrNull { !it.isPassword && it !== passwordNode }
    val focused = edits.firstOrNull { it.isFocused }
    var ok = false
    try {
        when {
            passwordNode != null && userNode != null && username.isNotEmpty() && password.isNotEmpty() -> {
                ok = setText(userNode, username) or setText(passwordNode, password)
            }
            focused != null && focused.isPassword && password.isNotEmpty() -> {
                ok = setText(focused, password)
                if (username.isNotEmpty() && userNode != null) ok = setText(userNode, username) || ok
            }
            focused != null && focused.isEditable -> {
                if (username.isNotEmpty() && !focused.isPassword) ok = setText(focused, username)
                if (password.isNotEmpty() && passwordNode != null) ok = setText(passwordNode, password) || ok
                else if (password.isNotEmpty() && focused.isPassword) ok = setText(focused, password) || ok
            }
            passwordNode != null && password.isNotEmpty() -> ok = setText(passwordNode, password)
            userNode != null && username.isNotEmpty() -> ok = setText(userNode, username)
        }
    } finally {
        for (n in edits) {
            if (n !== root) n.recycle()
        }
    }
    return ok
}

private fun collectEdits(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
    if (node == null) return
    val cls = node.className?.toString().orEmpty()
    val edit = node.isEditable || cls.contains("EditText")
    if (edit) out.add(node)
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        collectEdits(child, out)
    }
}

private fun looksLikeUser(n: AccessibilityNodeInfo): Boolean {
    val id = n.viewIdResourceName.orEmpty()
    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
    val desc = n.contentDescription?.toString().orEmpty()
    val s = "$id $hint $desc".lowercase()
    return listOf("user", "email", "phone", "account", "login", "name", "用户", "账号", "帐号", "手机", "邮箱").any { s.contains(it) }
}

private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
    val args = Bundle()
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}
