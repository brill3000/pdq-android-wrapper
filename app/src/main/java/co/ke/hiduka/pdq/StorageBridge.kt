package co.ke.hiduka.pdq

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * JS bridge exposed to the WebView as `window.HidukaStorage`.
 *
 * Surface is intentionally small:
 *   outboxAdd / outboxList / outboxRemove / outboxMarkAttempt /
 *   kvGet / kvSet / kvDelete
 *
 * All methods are synchronous — SQLite is fast enough that round-tripping
 * via Kotlin Promise or Coroutines would just add complexity without
 * latency benefit. Returns JSON strings (or null where appropriate) so
 * the PWA can parse with `JSON.parse` and not worry about JavascriptInterface
 * type marshalling quirks.
 *
 * Errors never propagate to the PWA. We log to WrapperLogger and return
 * a safe default so a bridge failure can never break the PWA's flow.
 */
class StorageBridge(context: Context) {
    private val storage = Storage.get(context)

    @JavascriptInterface
    fun outboxAdd(
        id: String,
        kind: String,
        variablesJson: String,
        createdAt: Long,
    ): String {
        return try {
            storage.outboxAdd(id, kind, variablesJson, createdAt)
            """{"ok":true}"""
        } catch (e: Throwable) {
            WrapperLogger.e("StorageBridge", "outboxAdd failed", e)
            """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
        }
    }

    @JavascriptInterface
    fun outboxRemove(id: String): String {
        return try {
            storage.outboxRemove(id)
            """{"ok":true}"""
        } catch (e: Throwable) {
            WrapperLogger.e("StorageBridge", "outboxRemove failed", e)
            """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
        }
    }

    @JavascriptInterface
    fun outboxMarkAttempt(id: String, error: String?): String {
        return try {
            storage.outboxMarkAttempt(id, error)
            """{"ok":true}"""
        } catch (e: Throwable) {
            WrapperLogger.e("StorageBridge", "outboxMarkAttempt failed", e)
            """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
        }
    }

    @JavascriptInterface
    fun outboxList(): String = try {
        storage.outboxList()
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "outboxList failed", e)
        "[]"
    }

    @JavascriptInterface
    fun outboxClear(): String = try {
        storage.outboxClear()
        """{"ok":true}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "outboxClear failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }

    @JavascriptInterface
    fun kvGet(key: String): String? = try {
        storage.kvGet(key)
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "kvGet failed", e)
        null
    }

    @JavascriptInterface
    fun kvSet(key: String, value: String): String = try {
        storage.kvSet(key, value)
        """{"ok":true}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "kvSet failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }

    @JavascriptInterface
    fun kvDelete(key: String): String = try {
        storage.kvDelete(key)
        """{"ok":true}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "kvDelete failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }
}
