package co.ke.hiduka.pdq

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS bridge exposed to the web app as `window.HidukaPrinter`.
 *
 * Every method must be annotated with @JavascriptInterface and have only
 * primitive / String args — Android will not expose un-annotated members
 * to the WebView.
 *
 * The actual print calls land in [NewlandPrinter], which today is a stub
 * that just logs. Drop the Newland NDK .aar into app/libs/, wire its
 * PrinterManager into NewlandPrinter, and JS-side printing lights up
 * with zero frontend changes.
 */
class PrinterBridge(private val context: Context) {

    private val printer = NewlandPrinter(context)

    @JavascriptInterface
    fun isAvailable(): Boolean = printer.isAvailable()

    @JavascriptInterface
    fun printText(text: String): String = wrapResult {
        printer.printText(text)
    }

    @JavascriptInterface
    fun printQrCode(data: String, sizePx: Int): String = wrapResult {
        printer.printQrCode(data, sizePx)
    }

    /**
     * Raw ESC/POS bytes, base64-encoded. The most flexible path — the
     * web app generates ESC/POS for the receipt and we just feed the
     * bytes to the printer. Use this for the production receipt flow.
     */
    @JavascriptInterface
    fun printEscPos(base64Bytes: String): String = wrapResult {
        val bytes = Base64.decode(base64Bytes, Base64.DEFAULT)
        printer.printRaw(bytes)
    }

    @JavascriptInterface
    fun cutPaper(): String = wrapResult { printer.cutPaper() }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val json = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("printerAvailable", printer.isAvailable())
        }
        return json.toString()
    }

    private inline fun wrapResult(block: () -> Unit): String {
        return try {
            block()
            JSONObject().put("ok", true).toString()
        } catch (e: Throwable) {
            Log.e("PrinterBridge", "print failed", e)
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName)
                .toString()
        }
    }
}
