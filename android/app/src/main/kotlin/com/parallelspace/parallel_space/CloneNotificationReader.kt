package com.parallelspace.parallel_space

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * 宿主进程读取自己仍在显示的通知，并按 tag 做单条清除。
 * 不记录标题和正文。
 */
object CloneNotificationReader {
    fun read(ctx: Context): Map<String, Any?> {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return mapOf("ok" to false, "error" to "unavailable")
            val shown = nm.activeNotifications ?: return mapOf("ok" to true, "items" to emptyList<Any>())
            val items = ArrayList<Map<String, Any>>()
            for (sbn in shown) {
                try {
                    if (CloneNotificationPolicy.parse(sbn.tag) == null) continue
                    val note = sbn.notification
                    items.add(
                        mapOf(
                            "tag" to (sbn.tag ?: ""),
                            "id" to sbn.id,
                            "postTime" to sbn.postTime,
                            "title" to textOf(note, Notification.EXTRA_TITLE),
                            "text" to textOf(note, Notification.EXTRA_TEXT),
                            "groupSummary" to (note != null && (note.flags and Notification.FLAG_GROUP_SUMMARY) != 0),
                        ),
                    )
                } catch (_: Throwable) {
                    // 一条畸形通知不能让整页变成读不到，也不把异常内容写进日志。
                }
            }
            mapOf("ok" to true, "items" to items)
        } catch (_: Throwable) {
            mapOf("ok" to false, "error" to "unavailable")
        }
    }

    fun cancel(ctx: Context, tag: String, id: Int): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return "unavailable"
        val shown = try {
            nm.activeNotifications
        } catch (_: Throwable) {
            return "unavailable"
        } ?: return "unavailable"
        val active = ArrayList<CloneNotificationInbox.Active>(shown.size)
        for (sbn in shown) {
            active.add(CloneNotificationInbox.Active(sbn.tag, sbn.id, sbn.postTime, "", "", false))
        }
        if (!CloneNotificationInbox.allowCancel(tag, id, active)) return "rejected"
        return try {
            nm.cancel(tag, id)
            val parsed = CloneNotificationPolicy.parse(tag)
            if (parsed != null) {
                try {
                    CloneNotificationIndex.forget(ctx.filesDir, parsed.pkg, parsed.userId, tag)
                } catch (_: Throwable) {
                }
            }
            "ok"
        } catch (_: Throwable) {
            "unavailable"
        }
    }

    private fun textOf(note: Notification?, key: String): String {
        val value = note?.extras?.getCharSequence(key)?.toString()?.trim() ?: return ""
        return value
    }
}
