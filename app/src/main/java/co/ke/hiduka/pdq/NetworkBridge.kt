package co.ke.hiduka.pdq

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Bridges Android's ConnectivityManager into the WebView. Exposed to JS
 * as `window.HidukaNetwork` (read-only state) plus a `hdq:network` event
 * dispatched on the window every time the system network changes.
 *
 * Why this exists: `navigator.onLine` inside an Android WebView is
 * famously unreliable — it's `true` on captive portals, lags on Wi-Fi
 * → cellular handover, and gives no detail about the connection type.
 * Native ConnectivityManager is the source of truth.
 *
 * Subscribe in JS:
 *
 * ```js
 * window.addEventListener('hdq:network', (e) => {
 *   const { connected, type } = e.detail;  // type: 'wifi'|'cellular'|'ethernet'|'other'|null
 * });
 *
 * JSON.parse(window.HidukaNetwork.getState())
 * ```
 */
class NetworkBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {
    private val cm: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            broadcast()
        }

        override fun onLost(network: Network) {
            broadcast()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            broadcast()
        }
    }

    fun start() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm?.registerDefaultNetworkCallback(callback)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(req, callback)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
            // Already unregistered or never registered — ignore.
        }
    }

    @JavascriptInterface
    fun getState(): String = currentState().toString()

    private fun broadcast() {
        val state = currentState().toString()
        val webView = webViewProvider() ?: return
        webView.post {
            // CustomEvent on window so PWA listeners can subscribe without
            // a polling loop. `detail` is the same JSON shape as getState().
            val js = """
                (function() {
                  try {
                    var s = $state;
                    window.dispatchEvent(new CustomEvent('hdq:network', { detail: s }));
                  } catch (e) { /* ignore */ }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    private fun currentState(): JSONObject {
        val json = JSONObject()
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        json.put("connected", connected)
        json.put(
            "type",
            when {
                caps == null -> JSONObject.NULL
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            },
        )
        return json
    }

    companion object {
        private const val TAG = "NetworkBridge"
    }
}
