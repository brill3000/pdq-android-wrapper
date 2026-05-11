package co.ke.hiduka.pdq

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

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
class AppBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {

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

    /**
     * Save a base64-encoded file to the Downloads folder. Used by the
     * PWA to persist artifacts generated client-side (PDFDownloadLink
     * blobs, CSV exports, etc.) — the standard WebView download flow
     * can't handle `blob:` URLs because they live in the WebView's
     * process memory and the system DownloadManager only accepts
     * http(s).
     *
     * Returns a JSON string `{ok, error?, path?}` so the PWA can show
     * a real toast instead of guessing.
     */
    @JavascriptInterface
    fun saveFile(base64Data: String, filename: String, mimeType: String?): String {
        WrapperLogger.i(
            "AppBridge",
            "saveFile",
            mapOf("filename" to filename, "mime" to (mimeType ?: ""), "bytes" to base64Data.length),
        )
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val safeMime = mimeType ?: "application/octet-stream"
            val outPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: MediaStore.Downloads. No permission needed; file
                // lands in the system Downloads folder visible to every
                // file manager + the Downloads tray notification.
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: throw IllegalStateException("Could not create Downloads entry")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not open Downloads stream")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } else {
                // Legacy path — direct write. Requires WRITE_EXTERNAL_STORAGE
                // for API < 29 which our manifest declares for that range.
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                if (!downloads.exists()) downloads.mkdirs()
                val target = File(downloads, filename)
                FileOutputStream(target).use { it.write(bytes) }
                target.absolutePath
            }

            val webView = webViewProvider()
            webView?.post {
                Toast.makeText(context, "Saved $filename to Downloads", Toast.LENGTH_SHORT).show()
            }
            """{"ok":true,"path":"${outPath.replace("\"", "\\\"")}"}"""
        } catch (e: Throwable) {
            WrapperLogger.e("AppBridge", "saveFile failed: ${e.message}", e)
            """{"ok":false,"error":"${(e.message ?: "save failed").replace("\"", "\\\"")}"}"""
        }
    }
}
