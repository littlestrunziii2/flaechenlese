package de.flaechenlese.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Prüft auf GitHub, ob eine neuere APK veröffentlicht wurde (Tag „android-v<Nr>“ = versionCode). */
object Updates {
    private const val API = "https://api.github.com/repos/littlestrunziii2/flaechenlese/releases/latest"
    const val APK_URL = "https://github.com/littlestrunziii2/flaechenlese/releases/latest/download/Flaechenlese.apk"
    private const val CHANNEL = "app_update"
    private const val PREFS = "updates"

    fun installedCode(c: Context): Int = try {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    } catch (e: Exception) { 0 }

    /** Liefert (Versionsnummer, Name) der neuesten Version oder null. Muss im Hintergrund laufen. */
    private fun latest(): Pair<Int, String>? = try {
        val con = URL(API).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 8000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val n = j.getString("tag_name").substringAfterLast("v").toInt()
        n to (j.optString("name").ifEmpty { "Version $n" })
    } catch (e: Exception) { null }

    /** Prüft im Hintergrund; ruft [onNewer] im Hauptthread auf, wenn es ein Update gibt. */
    fun check(c: Context, force: Boolean = false, onNewer: ((String) -> Unit)? = null) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("last", 0) < 3_600_000) return
        Thread {
            val l = latest() ?: return@Thread
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            if (l.first > installedCode(c)) {
                Handler(Looper.getMainLooper()).post { onNewer?.invoke(l.second) }
            }
        }.start()
    }

    /** Im Hintergrund (täglicher Alarm): Benachrichtigung zeigen, aber nur einmal pro Version. */
    fun backgroundCheck(c: Context) {
        val l = latest() ?: return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (l.first > installedCode(c) && prefs.getInt("notified", 0) < l.first) {
            notify(c, l.second)
            prefs.edit().putInt("notified", l.first).apply()
        }
    }

    fun notify(c: Context, name: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 40, Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(40, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Update für Flächenlese verfügbar")
            .setContentText("$name – zum Herunterladen tippen")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    /** Hinweis in der App mit Knopf zum Herunterladen. */
    fun showDialog(a: Activity, name: String) {
        if (a.isFinishing) return
        AlertDialog.Builder(a)
            .setTitle("Update verfügbar")
            .setMessage("$name ist verfügbar. Jetzt herunterladen und über die bestehende App installieren? Deine Daten bleiben erhalten.")
            .setPositiveButton("Herunterladen") { _, _ -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL))) }
            .setNegativeButton("Später", null)
            .show()
    }
}
