package de.flaechenlese.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Farben des aktuellen App-Designs (kommen aus der Web-App). Ohne Daten bleiben die Original-Farben. */
data class WColors(val bg: Int, val text: Int, val muted: Int, val btn: Int, val read: Int, val onRead: Int, val accent: Int, val onAccent: Int) {
    companion object {
        fun load(c: Context): WColors? {
            val j = Store.snapshot(c)?.optJSONObject("colors") ?: return null
            return try {
                WColors(
                    Color.parseColor(j.getString("bg")), Color.parseColor(j.getString("text")),
                    Color.parseColor(j.getString("muted")), Color.parseColor(j.getString("btn")),
                    Color.parseColor(j.getString("read")), Color.parseColor(j.getString("onRead")),
                    Color.parseColor(j.getString("accent")), Color.parseColor(j.getString("onAccent"))
                )
            } catch (e: Exception) { null }
        }
    }
}

/** Hintergrund (abgerundete Form) einfärben – ab Android 12 möglich. */
fun RemoteViews.tintBg(id: Int, color: Int) {
    if (Build.VERSION.SDK_INT >= 31) setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
}

/** Die drei Widgets wie in der Original-App: Heute, Fortschritt, Kalender. */
object Widgets {
    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        val today = m.getAppWidgetIds(ComponentName(c, ReadingWidget::class.java))
        if (today.isNotEmpty()) ReadingWidget.update(c, m, today)
        val progress = m.getAppWidgetIds(ComponentName(c, ProgressWidget::class.java))
        if (progress.isNotEmpty()) ProgressWidget.update(c, m, progress)
        val cal = m.getAppWidgetIds(ComponentName(c, CalendarWidget::class.java))
        if (cal.isNotEmpty()) CalendarWidget.update(c, m, cal)
    }

    fun openApp(c: Context, requestCode: Int, date: String? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (date != null) i.putExtra(MainActivity.EXTRA_OPEN_DATE, date)
        return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** „Bibellese: Heute“ – Bibelstelle, YouVersion-Knopf und Haken. */
class ReadingWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = update(c, m, ids)

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == ACTION_TOGGLE_READ) {
            Store.toggleFromWidget(c, Store.activePlan(c), Store.today())
            Widgets.updateAll(c)
        }
    }

    companion object {
        const val ACTION_TOGGLE_READ = "de.flaechenlese.app.ACTION_TOGGLE_READ"

        fun update(c: Context, m: AppWidgetManager, ids: IntArray) {
            val s = Store.snapshot(c)
            val today = Store.today()
            val d = Store.day(c, Store.activePlan(c), today)
            val views = RemoteViews(c.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widget_passage, d.passage ?: if (s == null) "App öffnen" else "Kein Eintrag")

            val toggle = PendingIntent.getBroadcast(
                c, 0, Intent(c, ReadingWidget::class.java).setAction(ACTION_TOGGLE_READ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_button_check, if (s != null && d.passage != null) toggle else Widgets.openApp(c, 1, today))

            if (d.url != null) {
                val bible = PendingIntent.getActivity(
                    c, 2, Intent(Intent.ACTION_VIEW, Uri.parse(d.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_button_bible, bible)
            }

            val col = WColors.load(c)
            if (d.read) {
                views.setImageViewResource(R.id.widget_button_check, R.drawable.ic_check_custom)
                views.setInt(R.id.widget_button_check, "setBackgroundResource", R.drawable.widget_button_background_checked)
                views.setInt(R.id.widget_button_check, "setColorFilter", col?.onRead ?: Color.WHITE)
            } else {
                views.setImageViewResource(R.id.widget_button_check, 0)
                views.setInt(R.id.widget_button_check, "setBackgroundResource", R.drawable.widget_button_background)
                views.setInt(R.id.widget_button_check, "setColorFilter", 0)
            }
            if (col != null) {
                views.tintBg(R.id.widget_root, col.bg)
                views.setTextColor(R.id.widget_title, col.text)
                views.setTextColor(R.id.widget_passage, col.text)
                views.tintBg(R.id.widget_button_bible, col.btn)
                views.setInt(R.id.widget_button_bible, "setColorFilter", col.text)
                views.tintBg(R.id.widget_button_check, if (d.read) col.read else col.btn)
            }

            val open = Widgets.openApp(c, 1, today)
            views.setOnClickPendingIntent(R.id.widget_title, open)
            views.setOnClickPendingIntent(R.id.widget_passage, open)
            m.updateAppWidget(ids, views)
        }
    }
}

/** „Bibellese: Fortschritt“ – gelesene Tage im aktuellen Monat. */
class ProgressWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = update(c, m, ids)

    companion object {
        fun update(c: Context, m: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            val days = today.lengthOfMonth()
            val reads = Store.reads(c, Store.activePlan(c))
            var count = 0
            for (day in 1..days) if (reads.contains(today.withDayOfMonth(day).toString())) count++
            val progress = (count * 100) / days

            val views = RemoteViews(c.packageName, R.layout.widget_progress)
            views.setTextViewText(R.id.widget_month_name, today.month.getDisplayName(TextStyle.FULL, Locale.GERMAN))
            views.setProgressBar(R.id.widget_progress_bar, 100, progress, false)
            views.setTextViewText(R.id.widget_progress_text, "$count/$days")
            WColors.load(c)?.let { col ->
                views.tintBg(R.id.widget_progress_root, col.bg)
                views.setTextColor(R.id.widget_progress_title, col.text)
                views.setTextColor(R.id.widget_month_name, col.text)
                views.setTextColor(R.id.widget_progress_text, col.muted)
                if (Build.VERSION.SDK_INT >= 31) {
                    views.setColorStateList(R.id.widget_progress_bar, "setProgressTintList", ColorStateList.valueOf(col.accent))
                    views.setColorStateList(R.id.widget_progress_bar, "setProgressBackgroundTintList", ColorStateList.valueOf(col.btn))
                }
            }
            val open = Widgets.openApp(c, 3)
            views.setOnClickPendingIntent(R.id.widget_progress_title, open)
            views.setOnClickPendingIntent(R.id.widget_month_name, open)
            views.setOnClickPendingIntent(R.id.widget_progress_bar, open)
            m.updateAppWidget(ids, views)
        }
    }
}

/** „Bibel Kalender“ – Monatsübersicht, Tippen öffnet den Tag. */
class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = update(c, m, ids)

    companion object {
        fun update(c: Context, m: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            for (id in ids) {
                val views = RemoteViews(c.packageName, R.layout.widget_calendar)
                views.setTextViewText(
                    R.id.calendar_widget_title,
                    "${today.month.getDisplayName(TextStyle.FULL, Locale.GERMAN).uppercase(Locale.GERMAN)} ${today.year}"
                )
                val svc = Intent(c, CalendarWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.calendar_widget_grid, svc)

                val template = PendingIntent.getActivity(
                    c, 20, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.calendar_widget_grid, template)
                views.setOnClickPendingIntent(R.id.calendar_widget_title, Widgets.openApp(c, 21))
                WColors.load(c)?.let { col ->
                    views.tintBg(R.id.widget_calendar_root, col.bg)
                    views.setTextColor(R.id.calendar_widget_title, col.text)
                    val muted = Color.argb(128, Color.red(col.text), Color.green(col.text), Color.blue(col.text))
                    listOf(R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6, R.id.wd7).forEach { views.setTextColor(it, muted) }
                }
                m.updateAppWidget(id, views)
            }
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.calendar_widget_grid)
        }
    }
}

class CalendarWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = CalendarFactory(applicationContext)
}

class CalendarFactory(private val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var days = listOf<Int>()
    private var reads = setOf<String>()
    private var first = LocalDate.now().withDayOfMonth(1)
    private var col: WColors? = null

    override fun onCreate() = load()
    override fun onDataSetChanged() = load()

    private fun load() {
        val today = LocalDate.now()
        first = today.withDayOfMonth(1)
        val offset = first.dayOfWeek.value - 1
        days = List(offset) { 0 } + (1..today.lengthOfMonth()).toList()
        reads = Store.reads(c, Store.activePlan(c))
        col = WColors.load(c)
    }

    override fun onDestroy() {}
    override fun getCount(): Int = days.size

    override fun getViewAt(position: Int): RemoteViews {
        val day = days.getOrElse(position) { 0 }
        val v = RemoteViews(c.packageName, R.layout.widget_calendar_item)
        if (day == 0) {
            v.setViewVisibility(R.id.calendar_item_text, View.INVISIBLE)
            return v
        }
        val date = first.withDayOfMonth(day)
        v.setViewVisibility(R.id.calendar_item_text, View.VISIBLE)
        v.setTextViewText(R.id.calendar_item_text, day.toString())
        val bg = when {
            reads.contains(date.toString()) -> R.drawable.widget_button_background_checked
            date == LocalDate.now() -> R.drawable.widget_today_background
            else -> R.drawable.widget_button_background
        }
        v.setInt(R.id.calendar_item_text, "setBackgroundResource", bg)
        v.setTextColor(R.id.calendar_item_text, Color.WHITE)
        col?.let { k ->
            when (bg) {
                R.drawable.widget_button_background_checked -> { v.tintBg(R.id.calendar_item_text, k.read); v.setTextColor(R.id.calendar_item_text, k.onRead) }
                R.drawable.widget_today_background -> { v.tintBg(R.id.calendar_item_text, k.accent); v.setTextColor(R.id.calendar_item_text, k.onAccent) }
                else -> { v.tintBg(R.id.calendar_item_text, k.btn); v.setTextColor(R.id.calendar_item_text, k.text) }
            }
        }
        val fill = Intent().putExtra(MainActivity.EXTRA_OPEN_DATE, date.toString())
        v.setOnClickFillInIntent(R.id.calendar_item_container, fill)
        v.setOnClickFillInIntent(R.id.calendar_item_text, fill)
        return v
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
