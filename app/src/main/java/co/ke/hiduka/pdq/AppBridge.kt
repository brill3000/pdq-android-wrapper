package co.ke.hiduka.pdq

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Misc app-control bridge exposed to the WebView as `window.HidukaApp`.
 * Today: just reload. Future: quit(), update(), wipeCache(), etc.
 *
 * Reload runs on the UI thread (WebView APIs are main-thread only). The
 * `hardReload` variant additionally clears the in-memory cache and
 * re-requests the index, which is what we want when the deploy-banner
 * fires after a new build — Android WebView's `window.location.reload()`
 * sometimes hands back the stale service-worker response.
 */
class AppBridge(private val webViewProvider: () -> WebView?) {

    @JavascriptInterface
    fun reload() {
        val webView = webViewProvider() ?: return
        webView.post { webView.reload() }
    }

    @JavascriptInterface
    fun hardReload() {
        val webView = webViewProvider() ?: return
        webView.post {
            webView.clearCache(false)
            webView.loadUrl(BuildConfig.WEB_APP_URL)
        }
    }
}
