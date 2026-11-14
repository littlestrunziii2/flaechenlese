package de.flaechenlese.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import java.io.File

/** Schnittstelle, über die die Web-App mit Android spricht (window.FLApp). */
class Bridge(private val a: MainActivity) {
    private val c = a.applicationContext

    @JavascriptInterface
    fun sync(json: String) {
        Store.saveSnapshot(c, json)
        Reminders.schedule(c)
        Widgets.updateAll(c)
    }

    @JavascriptInterface
    fun takePendingToggles(): String = Store.takePending(c)

    @JavascriptInterface
    fun saveFile(name: String, mime: String, base64: String): Boolean {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                c.contentResolver.update(uri, values, null, null)
            } else {
                val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
                File(dir, name).writeBytes(bytes)
            }
            a.runOnUiThread { Toast.makeText(a, "Gespeichert in „Downloads“: $name", Toast.LENGTH_LONG).show() }
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun share(text: String) {
        a.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            a.startActivity(Intent.createChooser(send, null))
        }
    }

    @JavascriptInterface
    fun copy(text: String) {
        a.runOnUiThread {
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Flächenlese", text))
        }
    }

    @JavascriptInterface
    fun print() {
        a.runOnUiThread { a.printPage() }
    }

    @JavascriptInterface
    fun setBars(bg: String, nav: String, light: Boolean) {
        a.runOnUiThread {
            try {
                a.window.statusBarColor = Color.parseColor(bg)
                a.window.navigationBarColor = Color.parseColor(nav)
                a.web.setBackgroundColor(Color.parseColor(bg))
                @Suppress("DEPRECATION")
                a.window.decorView.systemUiVisibility = if (light)
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.runOnUiThread { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5) }
        }
    }

    @JavascriptInterface
    fun testNotification(plansJson: String) {
        val arr = try { JSONArray(plansJson) } catch (e: Exception) { JSONArray() }
        Reminders.fire(c, "morning", true, (0 until arr.length()).map { arr.getString(it) })
    }

    @JavascriptInterface
    fun setBackupData(csv: String) { Backups.setData(c, csv) }

    @JavascriptInterface
    fun pickBackupFolder() {
        a.runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            try { a.startActivityForResult(i, MainActivity.REQ_FOLDER) } catch (e: Exception) {
                Toast.makeText(a, "Ordnerauswahl nicht verfügbar", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun backupStatus(): String = Backups.status(c)

    @JavascriptInterface
    fun backupNow(csv: String): String {
        Backups.setData(c, csv)
        return Backups.run(c)
    }

    private fun js(code: String) { a.runOnUiThread { a.web.evaluateJavascript(code, null) } }
    private fun q(s: String) = org.json.JSONObject.quote(s)

    @JavascriptInterface
    fun appVersion(): String = Updates.versionName(c)

    @JavascriptInterface
    fun checkUpdate() {
        Updates.checkNow(c) { l ->
            val o = org.json.JSONObject()
            if (l == null) o.put("error", true) else {
                o.put("available", l.first > Updates.installedCode(c)).put("name", l.second)
            }
            js("window.flUpdateInfo && window.flUpdateInfo(${o})")
        }
    }

    @JavascriptInterface
    fun installUpdate() {
        Updates.install(c, { p -> js("window.flUpdateProgress && window.flUpdateProgress($p)") }, { m -> js("window.flUpdateDone && window.flUpdateDone(${q(m)})") })
    }
}
