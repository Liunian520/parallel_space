package com.parallelspace.parallel_space

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** 桌面小组件：最多 4 个分身（真实图标），点击通过与快捷方式相同的跳板打开。 */
class CloneWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private const val PREFS = "clone_widget"
        private val CELLS = intArrayOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3)
        private val DOTS = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3)
        private val LABELS = intArrayOf(R.id.label0, R.id.label1, R.id.label2, R.id.label3)

        fun save(ctx: Context, items: List<Map<String, Any?>>) {
            val arr = JSONArray()
            for (m in items.take(4)) {
                val id = m["id"] as? String ?: continue
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("label", m["label"] as? String ?: "")
                    put("pkg", m["pkg"] as? String ?: "")
                })
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("items", arr.toString()) }
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CloneWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun load(ctx: Context): JSONArray = try {
            JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val items = load(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_clones)
            rv.setViewVisibility(R.id.empty, if (items.length() == 0) View.VISIBLE else View.GONE)
            val openApp = PendingIntent.getActivity(
                ctx, 200, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.empty, openApp)
            for (i in 0 until 4) {
                if (i >= items.length()) {
                    rv.setViewVisibility(CELLS[i], if (items.length() == 0) View.GONE else View.INVISIBLE)
                    continue
                }
                val o = items.getJSONObject(i)
                val id = o.optString("id")
                rv.setViewVisibility(CELLS[i], View.VISIBLE)
                rv.setTextViewText(LABELS[i], o.optString("label"))
                val pkg = o.optString("pkg")
                // 个人空间里的原图标；虚拟分身没有工作资料角标。
                val bmp = if (pkg.isEmpty()) null else {
                    WorkProfile.iconBitmap(ctx, pkg, false, 128)
                        ?: WorkProfile.iconBitmap(ctx, pkg, true, 128)
                }
                if (bmp != null) rv.setImageViewBitmap(DOTS[i], bmp) else rv.setImageViewResource(DOTS[i], R.mipmap.ic_launcher)
                val target = if (pkg.isEmpty()) MainActivity.cloneLaunchIntent(ctx, id) else Shortcuts.trampolineIntent(ctx, id, pkg)
                val pi = PendingIntent.getActivity(
                    ctx, 100 + i, target,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                rv.setOnClickPendingIntent(CELLS[i], pi)
            }
            for (wid in ids) mgr.updateAppWidget(wid, rv)
        }
    }
}
