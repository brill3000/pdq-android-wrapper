package co.ke.hiduka.pdq

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Wrapper-side log shipper. Mirrors `clientLogger` on the PWA — every
 * entry lands at the backend's POST /logs/client, prefixed [client]
 * and tagged source="wrapper" in the metadata.
 *
 * Design rules:
 *   - Never block the caller. Logs go into an in-memory queue and a
 *     dedicated HandlerThread drains them every 5 seconds.
 *   - Never throw. HTTP failures are dropped, never re-queued (avoids
 *     loops on a backend outage).
 *   - Never log the shipper's own errors via itself — that's a feedback
 *     loop into the wire. Logcat only.
 *   - Always also call android.util.Log so adb logcat keeps working.
 */
object WrapperLogger {
    private const val TAG = "WrapperLogger"
    private const val FLUSH_INTERVAL_MS = 5_000L
    private const val MAX_QUEUE = 500
    private const val MAX_MESSAGE_LEN = 2000

    private val queue = ConcurrentLinkedQueue<JSONObject>()
    private val thread = HandlerThread("WrapperLoggerFlusher").apply { start() }
    private val handler = Handler(thread.looper)
    private val flushRunnable = object : Runnable {
        override fun run() {
            drain()
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(flushRunnable)
        // One last flush so app-close logs make it out.
        drain()
        thread.quitSafely()
    }

    fun d(tag: String, message: String, context: Map<String, Any?>? = null) {
        Log.d(tag, message)
        enqueue("debug", tag, message, context)
    }

    fun i(tag: String, message: String, context: Map<String, Any?>? = null) {
        Log.i(tag, message)
        enqueue("info", tag, message, context)
    }

    fun w(tag: String, message: String, context: Map<String, Any?>? = null) {
        Log.w(tag, message)
        enqueue("warn", tag, message, context)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val ctx = throwable?.let {
            mapOf("stack" to Log.getStackTraceString(it).take(MAX_MESSAGE_LEN))
        }
        enqueue("error", tag, message, ctx)
    }

    private fun enqueue(
        level: String,
        tag: String,
        message: String,
        context: Map<String, Any?>?,
    ) {
        if (queue.size >= MAX_QUEUE) {
            // Drop quietly under pressure rather than OOM.
            queue.poll()
        }
        val entry = JSONObject().apply {
            put("level", level)
            put("message", "$tag: ${message.take(MAX_MESSAGE_LEN)}")
            put("source", "wrapper")
            put("timestamp", System.currentTimeMillis())
            put("appVersion", BuildConfig.VERSION_NAME)
            put(
                "context",
                JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("sdkInt", Build.VERSION.SDK_INT)
                    context?.forEach { (k, v) -> put(k, v) }
                },
            )
        }
        queue.add(entry)
    }

    private fun drain() {
        if (queue.isEmpty()) return
        val batch = mutableListOf<JSONObject>()
        while (batch.size < 100) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        val body = JSONObject().apply {
            put("entries", JSONArray(batch))
        }.toString()

        try {
            val url = URL(BuildConfig.LOGS_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            // Drain the response stream so HttpURLConnection's internal
            // pool can recycle the socket on the next batch — we don't
            // call disconnect() for the same reason.
            conn.inputStream.use { it.readBytes() }
        } catch (e: Throwable) {
            // Don't re-queue. Don't recurse into this logger. Logcat only.
            Log.w(TAG, "drain failed: ${e.message}")
        }
    }
}
