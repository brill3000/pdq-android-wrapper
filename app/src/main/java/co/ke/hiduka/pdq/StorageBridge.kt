package co.ke.hiduka.pdq

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * JS bridge exposed to the WebView as `window.HidukaStorage`.
 *
 * Three method families:
 *   outbox*       — pending mutations awaiting replay (sales today).
 *   kv*           — auth tokens, deviceId, small bootstrap values.
 *   catalog*      — products + customers cache, populated by the sync engine.
 *   syncState*    — per-entity pull/push cursors so incremental sync works.
 *   syncLog*      — audit trail (every pull/push outcome) for support.
 *
 * All methods synchronous. Returns are JSON strings (or null) so the PWA
 * can JSON.parse with a stable shape. Errors never propagate — every call
 * is wrapped, logged via WrapperLogger, and returns a safe default so a
 * bridge failure can never break the PWA's flow.
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

    // ---- catalog --------------------------------------------------------

    @JavascriptInterface
    fun catalogBulkUpsert(table: String, rowsJson: String): String = try {
        val n = storage.catalogBulkUpsert(table, rowsJson)
        """{"ok":true,"count":$n}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "catalogBulkUpsert($table) failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }

    @JavascriptInterface
    fun catalogList(table: String, includeDeleted: Boolean): String = try {
        storage.catalogList(table, includeDeleted)
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "catalogList($table) failed", e)
        "[]"
    }

    @JavascriptInterface
    fun catalogMaxUpdatedAt(table: String): Long = try {
        storage.catalogMaxUpdatedAt(table)
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "catalogMaxUpdatedAt($table) failed", e)
        0L
    }

    @JavascriptInterface
    fun catalogClear(table: String): String = try {
        storage.catalogClear(table)
        """{"ok":true}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "catalogClear($table) failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }

    // ---- sync_state -----------------------------------------------------

    @JavascriptInterface
    fun syncStateGet(entity: String): String = try {
        storage.syncStateGet(entity)
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "syncStateGet($entity) failed", e)
        "{}"
    }

    @JavascriptInterface
    fun syncStateSet(
        entity: String,
        cursor: Long,
        lastPullAt: Long,
        lastPushAt: Long,
    ): String = try {
        // The bridge can't pass nullable Longs (JNI marshalling), so we use
        // sentinel -1 to mean "leave unchanged". TS wrapper translates
        // undefined → -1 before calling.
        val c: Long? = if (cursor < 0) null else cursor
        val pull: Long? = if (lastPullAt < 0) null else lastPullAt
        val push: Long? = if (lastPushAt < 0) null else lastPushAt
        storage.syncStateSet(entity, c, pull, push)
        """{"ok":true}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "syncStateSet($entity) failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }

    // ---- sync_log -------------------------------------------------------

    @JavascriptInterface
    fun syncLogAppend(
        at: Long,
        direction: String,
        entity: String,
        refId: String?,
        cursor: Long,
        count: Int,
        status: String,
        error: String?,
        deviceId: String?,
        durationMs: Long,
    ): String = try {
        // Same sentinel-encoding for "unset" as syncStateSet: -1 → null.
        val c: Long? = if (cursor < 0) null else cursor
        val n: Int? = if (count < 0) null else count
        val d: Long? = if (durationMs < 0) null else durationMs
        storage.syncLogAppend(at, direction, entity, refId, c, n, status, error, deviceId, d)
        """{"ok":true}"""
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "syncLogAppend failed", e)
        """{"ok":false,"error":"${e.message ?: "unknown"}"}"""
    }

    @JavascriptInterface
    fun syncLogList(limit: Int): String = try {
        storage.syncLogList(limit)
    } catch (e: Throwable) {
        WrapperLogger.e("StorageBridge", "syncLogList failed", e)
        "[]"
    }
}
