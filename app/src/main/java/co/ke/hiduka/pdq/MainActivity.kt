package co.ke.hiduka.pdq

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import co.ke.hiduka.pdq.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var bridge: PrinterBridge
    private lateinit var network: NetworkBridge
    private lateinit var app: AppBridge
    private lateinit var storage: StorageBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep the WebView below the system status bar so the PWA's
        // app bar / dashboard header doesn't get drawn under the clock,
        // signal and battery icons. We let Android handle the inset
        // automatically by *not* opting into edge-to-edge layout.
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Defensive — if Android still hands us insets (some
        // manufacturers default to immersive on POS devices), pad the
        // WebView container by the top inset so the WebView never
        // overlaps the status bar regardless of vendor quirks.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        webView = binding.webview
        bridge = PrinterBridge(this)
        network = NetworkBridge(this) { webView }
        app = AppBridge(this) { webView }
        storage = StorageBridge(this)

        // Pull-to-refresh — only enabled when the WebView is scrolled
        // to the top, otherwise it fights with the page's own scroll.
        binding.swiperefresh.setOnRefreshListener {
            webView.reload()
        }
        webView.viewTreeObserver.addOnScrollChangedListener {
            binding.swiperefresh.isEnabled = webView.scrollY == 0
        }
        // Dismiss the spinner once the page finishes (or errors).
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swiperefresh.isRefreshing = false
            }
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                binding.swiperefresh.isRefreshing = false
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString HidukaPDQ/${BuildConfig.VERSION_NAME}"
        }

        // JS-side bridges: HidukaPrinter, HidukaNetwork, HidukaApp, HidukaStorage.
        webView.addJavascriptInterface(bridge, "HidukaPrinter")
        webView.addJavascriptInterface(network, "HidukaNetwork")
        webView.addJavascriptInterface(app, "HidukaApp")
        webView.addJavascriptInterface(storage, "HidukaStorage")

        webView.webChromeClient = WebChromeClient()

        // Downloads. Android WebView ignores download intents by default,
        // so receipts / exports just silently drop. Hook the listener and
        // route through DownloadManager for normal http(s) URLs, plus a
        // base64 inline handler for the data: URIs @react-pdf generates.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }

        webView.loadUrl(BuildConfig.WEB_APP_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        network.start()
        WrapperLogger.start()
        WrapperLogger.i(TAG, "wrapper boot", mapOf("webAppUrl" to BuildConfig.WEB_APP_URL))
        scheduleOutboxSync()
    }

    private fun scheduleOutboxSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<OutboxSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "hdq-outbox-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun onDestroy() {
        network.stop()
        WrapperLogger.stop()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * WebView download dispatcher. Two paths:
     *
     *  1. data: URIs (the most common path for the PWA — @react-pdf
     *     generates PDFs as `data:application/pdf;base64,...`). We
     *     decode + write to Downloads ourselves; DownloadManager
     *     refuses data URIs.
     *  2. http(s) URLs (e.g. invoice PDF served by the backend). Pass
     *     to DownloadManager so the user gets a real notification + a
     *     proper file in Downloads with cookies + UA carried over.
     */
    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            WrapperLogger.i(
                "MainActivity",
                "download requested",
                mapOf(
                    "scheme" to url.substringBefore(':'),
                    "filename" to filename,
                    "mime" to (mimeType ?: ""),
                ),
            )
            if (url.startsWith("data:")) {
                val idx = url.indexOf("base64,")
                if (idx < 0) {
                    Toast.makeText(this, "Unsupported data URL", Toast.LENGTH_SHORT).show()
                    return
                }
                app.saveFile(url.substring(idx + "base64,".length), filename, mimeType)
                return
            }
            if (url.startsWith("blob:")) {
                // blob: URLs only exist in the WebView's renderer process —
                // DownloadManager can't fetch them. Inject a reader that
                // pulls the blob via fetch + FileReader and routes the
                // base64 payload back through AppBridge.saveFile.
                readBlobAndSave(url, filename, mimeType)
                return
            }
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(filename)
                setDescription("Downloading $filename")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                // Carry the WebView's cookies + UA so authenticated PDFs work.
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url) ?: "")
                addRequestHeader("User-Agent", webView.settings.userAgentString)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(this, "Downloading $filename", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "download failed: ${e.message}", e)
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBlobAndSave(blobUrl: String, filename: String, mimeType: String?) {
        // Escape values for safe template interpolation into JS.
        val safeUrl = blobUrl.replace("'", "\\'")
        val safeName = filename.replace("'", "\\'").replace("\"", "\\\"")
        val safeMime = (mimeType ?: "").replace("'", "\\'").replace("\"", "\\\"")
        val js = """
            (function() {
              fetch('$safeUrl')
                .then(function(r) { return r.blob(); })
                .then(function(blob) {
                  return new Promise(function(resolve, reject) {
                    var reader = new FileReader();
                    reader.onload = function() { resolve(reader.result); };
                    reader.onerror = function() { reject(reader.error); };
                    reader.readAsDataURL(blob);
                  });
                })
                .then(function(dataUrl) {
                  var idx = dataUrl.indexOf('base64,');
                  if (idx < 0) throw new Error('blob did not encode to base64');
                  var b64 = dataUrl.substring(idx + 7);
                  if (window.HidukaApp && typeof window.HidukaApp.saveFile === 'function') {
                    window.HidukaApp.saveFile(b64, "$safeName", "$safeMime");
                  }
                })
                .catch(function(e) {
                  console.error('blob download failed', e);
                });
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
