package co.ke.hiduka.pdq

import android.content.Context
import android.util.Log

/**
 * Newland NB60 thermal printer adapter.
 *
 * **Currently stubbed.** Newland's NDK SDK is shipped as an .aar/.jar
 * under their developer agreement — drop it in `app/libs/` then replace
 * the stubs below with real calls.
 *
 * The typical Newland printer API (NDK SDK >= 2.0) looks roughly like:
 *
 * ```kotlin
 * val printer = DeviceServiceManager.getInstance().getPrinter()
 * printer.appendText("Hello", 24, 0, 0)        // text, font size, style, align
 * printer.appendQrCode(data, size, alignCenter)
 * printer.startPrint(callback)
 * printer.cutPaper(PrinterCutMode.FULL)
 * ```
 *
 * For raw ESC/POS bytes, Newland exposes `printer.printBitmap(bitmap)`
 * or `printer.printRaster(rasterData)`. Adapt [printRaw] accordingly.
 */
class NewlandPrinter(private val context: Context) {

    fun isAvailable(): Boolean {
        // TODO: replace with `try { DeviceServiceManager.getInstance().printer != null } catch { false }`
        return false
    }

    fun printText(text: String) {
        Log.i(TAG, "stub printText: $text")
        throw NotImplementedError(STUB_MSG)
    }

    fun printQrCode(data: String, sizePx: Int) {
        Log.i(TAG, "stub printQrCode: $data ($sizePx px)")
        throw NotImplementedError(STUB_MSG)
    }

    fun printRaw(bytes: ByteArray) {
        Log.i(TAG, "stub printRaw: ${bytes.size} bytes")
        throw NotImplementedError(STUB_MSG)
    }

    fun cutPaper() {
        Log.i(TAG, "stub cutPaper")
        throw NotImplementedError(STUB_MSG)
    }

    companion object {
        private const val TAG = "NewlandPrinter"
        private const val STUB_MSG =
            "NewlandPrinter is stubbed. Drop the Newland NDK .aar into app/libs/ " +
                "and replace the stubs in NewlandPrinter.kt with real SDK calls."
    }
}
