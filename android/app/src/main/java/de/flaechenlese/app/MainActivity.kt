package de.flaechenlese.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Zeigt die Flächenlese-Web-App an und verbindet sie mit Widget und Benachrichtigungen. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_OPEN_DATE = "open_date"
        const val START_URL = "https://littlestrunziii2.github.io/flaechenlese/"
        private const val HOST = "littlestrunziii2.github.io"
        private const val REQ_FILE = 7
    }

    lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageLoaded = false
    private var pendingImport: String? = null
    private var pendingOpenDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0F0F0F"))
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(this), "FLApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST && (u.path ?: "").startsWith("/flaechenlese")) return false
                openExternal(u)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                deliverImport()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent.createChooser(i, "Datei wählen"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(START_URL)
        readImport(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readImport(intent)
    }

    /** Daten, die aus der Browser-Version per Link übertragen werden (flaechenlese://import?d=...). */
    private fun readImport(i: Intent?) {
        i?.getStringExtra(EXTRA_OPEN_DATE)?.let { d ->
            if (d.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) { pendingOpenDate = d; i?.removeExtra(EXTRA_OPEN_DATE); deliverImport() }
        }
        val u = i?.data ?: return
        if (u.scheme != "flaechenlese" || u.host != "import") return
        val d = u.getQueryParameter("d") ?: return
        if (!d.matches(Regex("^[A-Za-z0-9_-]+$"))) return
        pendingImport = d
        i.data = null
        deliverImport()
    }

    private fun deliverImport() {
        if (!pageLoaded) return
        pendingOpenDate?.let { day ->
            pendingOpenDate = null
            web.postDelayed({ web.evaluateJavascript("window.flOpenDay && window.flOpenDay('$day')", null) }, 400)
        }
        val d = pendingImport ?: return
        pendingImport = null
        web.postDelayed({ web.evaluateJavascript("window.flImport && window.flImport('$d')", null) }, 600)
    }

    fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.flNativeResume && window.flNativeResume()", null)
        Updates.check(this) { name -> Updates.showDialog(this, name) }
    }

    override fun onPause() {
        super.onPause()
        Widgets.updateAll(this)
    }

    fun printPage() {
        val pm = getSystemService(PrintManager::class.java) ?: return
        pm.print("Flächenlese", web.createPrintDocumentAdapter("Flaechenlese"), PrintAttributes.Builder().build())
    }
}
