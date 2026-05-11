package co.ke.hiduka.pdq

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hands an already-rendered PDF file to Android's PrintManager. We don't
 * re-paginate, scale, or rasterise — we just stream the bytes into the
 * file descriptor the framework gives us. Works with any registered
 * Print Service on the device (Newland's built-in printer, Cloud Print
 * adapters, "Save as PDF", etc.).
 */
class PdfPrintAdapter(
    private val source: File,
    private val jobName: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        // Layout is "changed" the first time only — subsequent layout
        // requests with the same attributes report unchanged so the
        // framework doesn't redo work.
        val changed = oldAttributes != newAttributes
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onWriteCancelled()
                            return
                        }
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Throwable) {
            callback.onWriteFailed(e.message ?: e.javaClass.simpleName)
        }
    }
}
