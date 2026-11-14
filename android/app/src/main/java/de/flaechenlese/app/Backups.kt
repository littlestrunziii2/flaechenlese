package de.flaechenlese.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Automatische Backups (CSV) in einen frei gewählten Ordner, auch Cloud-Ordner wie Google Drive. */
object Backups {
    private const val PREFS = "backups"
    private const val PREFIX = "Flaechenlese_Backup_"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun csvFile(c: Context) = File(c.filesDir, "backup.csv")

    fun setData(c: Context, csv: String) { try { csvFile(c).writeText(csv) } catch (e: Exception) { } }

    fun folder(c: Context): Uri? = prefs(c).getString("uri", null)?.let { Uri.parse(it) }

    fun setFolder(c: Context, uri: Uri) {
        var name = "Ordner"
        try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (e: Exception) { }
        prefs(c).edit().putString("uri", uri.toString()).putString("name", name).apply()
    }

    fun folderName(c: Context): String = prefs(c).getString("name", "") ?: ""

    fun status(c: Context): String {
        val p = prefs(c)
        return JSONObject()
            .put("folder", if (folder(c) != null) folderName(c) else "")
            .put("last", p.getLong("last", 0))
            .put("ok", p.getBoolean("ok", true))
            .put("msg", p.getString("msg", "") ?: "")
            .toString()
    }

    private fun config(c: Context): JSONObject? = Store.snapshot(c)?.optJSONObject("backup")

    /** Sichert, wenn aktiviert und das Intervall abgelaufen ist (oder erzwungen). */
    fun runIfDue(c: Context, force: Boolean = false) {
        val cfg = config(c)
        if (!force && cfg?.optBoolean("enabled", false) != true) return
        val every = (cfg?.optInt("every", 1) ?: 1).coerceAtLeast(1)
        val last = prefs(c).getLong("last", 0)
        if (!force && last != 0L && System.currentTimeMillis() - last < every * 86_400_000L - 2 * 3_600_000L) return
        val r = run(c)
        if (!r.startsWith("ok")) notifyError(c, r.removePrefix("error:"))
    }

    /** Schreibt die Datei und räumt alte Backups auf. Antwort: "ok:<Dateiname>" oder "error:<Grund>". */
    fun run(c: Context): String = synchronized(lock) {
        val p = prefs(c)
        fun fail(msg: String): String {
            p.edit().putBoolean("ok", false).putString("msg", msg).apply()
            return "error:$msg"
        }
        val tree = folder(c) ?: return fail("Kein Ordner gewählt")
        val f = csvFile(c)
        if (!f.exists() || f.length() == 0L) return fail("Noch keine Daten – bitte die App einmal öffnen")
        val keep = (config(c)?.optInt("keep", 5) ?: 5).coerceIn(1, 99)
        try {
            val cr = c.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".csv"
            val doc = DocumentsContract.createDocument(cr, parent, "text/csv", name) ?: return fail("Datei konnte nicht angelegt werden")
            cr.openOutputStream(doc, "wt")?.use { it.write(f.readBytes()) } ?: return fail("Datei konnte nicht geschrieben werden")

            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val mine = ArrayList<Pair<String, String>>()
            cr.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (it.moveToNext()) {
                    val n = it.getString(1) ?: continue
                    if (n.startsWith(PREFIX) && n.contains(".csv")) mine.add(it.getString(0) to n)
                }
            }
            mine.sortBy { it.second }
            var extra = mine.size - keep
            for ((id, _) in mine) {
                if (extra <= 0) break
                try { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (e: Exception) { }
                extra--
            }
            p.edit().putLong("last", System.currentTimeMillis()).putBoolean("ok", true).putString("msg", "").apply()
            "ok:$name"
        } catch (e: SecurityException) {
            fail("Zugriff auf den Ordner fehlt – bitte Ordner neu wählen")
        } catch (e: Exception) {
            fail(e.message ?: "Unbekannter Fehler")
        }
    }

    private fun notifyError(c: Context, msg: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("backup", "Backup", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(50, Notification.Builder(c, "backup")
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Automatisches Backup fehlgeschlagen")
            .setContentText(msg)
            .setContentIntent(Widgets.openApp(c, 50))
            .setAutoCancel(true).build())
    }
}
