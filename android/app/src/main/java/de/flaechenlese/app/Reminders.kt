package de.flaechenlese.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Calendar

/**
 * Benachrichtigungen wie in der Original-App:
 * morgens und abends, nur für die ausgewählten Lesepläne
 * und nur, wenn die heutige Lesung noch NICHT als gelesen markiert ist.
 */
object Reminders {
    private const val CHANNEL = "bible_reminder"
    private const val ACTION = "de.flaechenlese.app.REMINDER"

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(c, "morning"))
        am.cancel(pending(c, "evening"))
        am.cancel(pending(c, "midnight"))
        setAlarm(c, am, "midnight", 0, 1) // Widgets nach Mitternacht aktualisieren
        val n = Store.snapshot(c)?.optJSONObject("notif") ?: return
        if (!n.optBoolean("enabled", false)) return
        setAlarm(c, am, "morning", n.optInt("mh", 8), n.optInt("mm", 0))
        setAlarm(c, am, "evening", n.optInt("eh", 20), n.optInt("em", 0))
    }

    private fun setAlarm(c: Context, am: AlarmManager, type: String, h: Int, m: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending(c, type))
    }

    private fun pending(c: Context, type: String): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction(ACTION).putExtra("type", type)
        return PendingIntent.getBroadcast(
            c, when (type) { "morning" -> 1; "evening" -> 2; else -> 3 }, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun fire(c: Context, type: String, test: Boolean = false, testPlans: List<String>? = null) {
        val s = Store.snapshot(c)
        val n = s?.optJSONObject("notif")
        if (!test && n?.optBoolean("enabled", false) != true) return

        var plans = testPlans ?: n?.optJSONArray("plans")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        if (plans.isEmpty()) plans = listOf("LARGE")

        val lines = mutableListOf<String>()
        for (plan in plans) {
            if (s != null && !Store.hasPlan(c, plan)) continue
            val d = Store.day(c, plan)
            if (!d.read) {
                val name = Store.planName(c, plan)
                val short = if (name.length > 18) name.take(16) + ".." else name
                val passage = (d.passage ?: "Heutiger Leseabschnitt").replace("\n", " / ")
                lines += "$passage ($short)"
            }
        }
        // Alles gelesen -> keine Benachrichtigung
        if (lines.isEmpty() && !test) return
        if (lines.isEmpty()) lines += "Heute ist schon alles gelesen ✅"
        show(c, type, lines, test)
    }

    private fun show(c: Context, type: String, lines: List<String>, test: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bibel Leseplan Erinnerung", NotificationManager.IMPORTANCE_DEFAULT))

        val title = if (type == "morning") "Zeit zum Lesen! 📖" else "Nicht vergessen! ✨"
        val summary = if (type == "morning") "Ausstehende Lesungen für heute" else "Du hast heute noch offene Lesungen"
        val style = Notification.InboxStyle().setSummaryText(summary)
        lines.forEach { style.addLine(it) }

        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(if (test) "[TEST] $title" else title)
            .setContentText(if (lines.size == 1) lines[0] else summary)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(if (type == "morning") 1 else 2, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val type = i.getStringExtra("type") ?: "morning"
        if (type != "midnight") Reminders.fire(c, type)
        Reminders.schedule(c)
        Widgets.updateAll(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Reminders.schedule(c)
        Widgets.updateAll(c)
    }
}
