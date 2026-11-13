package de.flaechenlese.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Widget „Heute“: Bibelstelle des aktiven Plans mit Gelesen-Knopf. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        m.updateAppWidget(ids, build(c))
    }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == ACTION_TOGGLE) {
            val plan = Store.snapshot(c)?.optString("active", "LARGE") ?: return
            Store.toggleFromWidget(c, plan, Store.today())
            updateAll(c)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "de.flaechenlese.app.TOGGLE"

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, TodayWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, build(c))
        }

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_today)
            val s = Store.snapshot(c)
            val plan = s?.optString("active", "LARGE") ?: "LARGE"
            val d = Store.day(c, plan)
            val date = LocalDate.now()

            v.setTextViewText(R.id.plan, s?.optString("activeName", "FLÄCHENLESE") ?: "FLÄCHENLESE")
            v.setTextViewText(R.id.date, date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)))
            v.setTextViewText(R.id.passage, d.passage ?: if (s == null) "App öffnen zum Starten" else "Kein Eintrag")

            if (d.read) {
                v.setTextViewText(R.id.btn, "✓ Gelesen")
                v.setInt(R.id.btn, "setBackgroundResource", R.drawable.widget_btn_done)
                v.setTextColor(R.id.btn, Color.WHITE)
            } else {
                v.setTextViewText(R.id.btn, "Als gelesen markieren")
                v.setInt(R.id.btn, "setBackgroundResource", R.drawable.widget_btn)
                v.setTextColor(R.id.btn, Color.parseColor("#141414"))
            }

            val open = PendingIntent.getActivity(
                c, 10,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.root, open)

            if (s != null && d.passage != null) {
                val toggle = PendingIntent.getBroadcast(
                    c, 11,
                    Intent(c, TodayWidget::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                v.setOnClickPendingIntent(R.id.btn, toggle)
            } else {
                v.setOnClickPendingIntent(R.id.btn, open)
            }
            return v
        }
    }
}
