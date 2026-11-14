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
            c, 40, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_OPEN_UPDATE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(40, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Update für Flächenlese verfügbar")
            .setContentText("$name – zum Installieren tippen")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    /** Hinweis in der App mit Knopf zum Herunterladen. */
    fun showDialog(a: MainActivity, name: String) {
        if (a.isFinishing) return
        AlertDialog.Builder(a)
            .setTitle("Update verfügbar")
            .setMessage("$name ist verfügbar. Jetzt direkt in der App aktualisieren? Deine Daten bleiben erhalten.")
            .setPositiveButton("Aktualisieren") { _, _ -> a.web.evaluateJavascript("window.flOpenUpdate && window.flOpenUpdate()", null) }
            .setNegativeButton("Später", null)
            .show()
    }

    /** Prüft sofort (Hintergrund) und meldet Ergebnis: Pair(neueste Nummer, Name) oder null bei Fehler. */
    fun checkNow(c: Context, cb: (Pair<Int, String>?) -> Unit) {
        Thread { cb(latest()) }.start()
    }

    fun versionName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Lädt die neue APK und startet die Installation (Android fragt einmal nach Bestätigung). */
    fun install(c: Context, onProgress: (Int) -> Unit, onDone: (String) -> Unit) {
        Thread {
            try {
                val pm = c.packageManager
                if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                    c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDone("Bitte „Installieren aus dieser Quelle“ erlauben und dann nochmal auf „Aktualisieren“ tippen.")
                    return@Thread
                }
                val file = java.io.File(c.cacheDir, "update.apk")
                val con = URL(APK_URL).openConnection() as HttpURLConnection
                con.connectTimeout = 15000; con.readTimeout = 30000; con.instanceFollowRedirects = true
                val total = con.contentLengthLong
                var done = 0L; var lastPct = -1
                con.inputStream.use { inp -> file.outputStream().use { out ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != lastPct) { lastPct = p; onProgress(p) } }
                    }
                } }
                if (file.length() < 100_000) { onDone("Download unvollständig – bitte später erneut versuchen."); return@Thread }
                val inst = pm.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(c.packageName)
                val id = inst.createSession(params)
                inst.openSession(id).use { s ->
                    s.openWrite("app", 0, file.length()).use { o -> file.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                    val pi = PendingIntent.getBroadcast(c, 41, Intent(c, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(pi.intentSender)
                }
                onDone("Installation gestartet – bitte bestätigen.")
            } catch (e: Exception) {
                onDone("Fehlgeschlagen: " + (e.message ?: "unbekannter Fehler"))
            }
        }.start()
    }
}

/** Empfängt das Ergebnis der Installation und zeigt Android die Bestätigungsabfrage. */
class UpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) c.startActivity(confirm)
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> { }
            else -> android.widget.Toast.makeText(c, "Update nicht installiert: " + (i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
