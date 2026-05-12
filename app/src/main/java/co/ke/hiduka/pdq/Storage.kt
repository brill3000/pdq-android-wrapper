package co.ke.hiduka.pdq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native SQLite store that is the durable source of truth on the device.
 *
 * Tables:
 *  - `outbox`     — queued GraphQL mutations awaiting replay (e.g. sales).
 *  - `kv`         — small key-value store for cached user, JWT, deviceId.
 *  - `products`   — cached product catalog. Pulled by the sync engine on
 *                   reconnect and read by the POS terminal while offline.
 *  - `customers`  — cached customer directory. Same pattern.
 *  - `sync_state` — per-entity cursor + last-pull/last-push timestamps so
 *                   incremental sync (`updatedAfter`) is cheap.
 *  - `sync_log`   — audit trail of every pull/push for support to inspect
 *                   without ADB. Rolling cap, oldest rows trimmed.
 *
 * Why SQLite is authoritative: WebView IndexedDB is wiped under memory
 * pressure on PDQ hardware. SQLite isn't. So a cashier offline-queues a
 * sale, the OS kills the WebView, IDB is gone, SQLite is intact, and the
 * WorkManager OutboxSyncWorker still replays the sale without the PWA
 * being open. Same logic for read caches: catalog stays warm even when
 * the WebView's storage gets evicted overnight.
 *
 * All catalog rows are stored as JSON `data` blobs to keep the schema
 * stable as the server-side product / customer model evolves — only id +
 * updated_at + deleted_at are first-class columns since the sync engine
 * needs them for cursor math and tombstone handling.
 */
class Storage(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createOutboxAndKv(db)
        createCatalogAndSync(db)
        createShiftsAndInvoices(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2: introduce catalog cache + sync metadata.
            createCatalogAndSync(db)
        }
        if (oldVersion < 3) {
            // v2 → v3: shifts + invoices cache (Phase 2 of offline sync).
            createShiftsAndInvoices(db)
        }
    }

    private fun createOutboxAndKv(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS outbox (
              id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              variables TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              attempts INTEGER NOT NULL DEFAULT 0,
              last_attempted_at INTEGER,
              last_error TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON outbox(created_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS kv (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createCatalogAndSync(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS products (
              id TEXT PRIMARY KEY,
              data TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              deleted_at INTEGER,
              cached_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_updated_at ON products(updated_at DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS customers (
              id TEXT PRIMARY KEY,
              data TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              deleted_at INTEGER,
              cached_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_updated_at ON customers(updated_at DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_state (
              entity TEXT PRIMARY KEY,
              cursor INTEGER,
              last_pull_at INTEGER,
              last_push_at INTEGER
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              at INTEGER NOT NULL,
              direction TEXT NOT NULL,
              entity TEXT NOT NULL,
              ref_id TEXT,
              cursor INTEGER,
              count INTEGER,
              status TEXT NOT NULL,
              error TEXT,
              device_id TEXT,
              duration_ms INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_log_at ON sync_log(at DESC)")
    }

    private fun createShiftsAndInvoices(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shifts (
              id TEXT PRIMARY KEY,
              data TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              deleted_at INTEGER,
              cached_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shifts_updated_at ON shifts(updated_at DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS invoices (
              id TEXT PRIMARY KEY,
              data TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              deleted_at INTEGER,
              cached_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_invoices_updated_at ON invoices(updated_at DESC)")
    }

    // ---- outbox ----------------------------------------------------------

    fun outboxAdd(
        id: String,
        kind: String,
        variablesJson: String,
        createdAt: Long,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("kind", kind)
            put("variables", variablesJson)
            put("created_at", createdAt)
            put("attempts", 0)
        }
        writableDatabase.insertWithOnConflict(
            "outbox",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun outboxRemove(id: String) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id))
    }

    fun outboxMarkAttempt(id: String, error: String?) {
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1, last_attempted_at = ?, last_error = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), error, id),
        )
    }

    fun outboxList(): String {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, kind, variables, created_at, attempts, last_attempted_at, last_error FROM outbox ORDER BY created_at ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = JSONObject().apply {
                    put("id", cursor.getString(0))
                    put("kind", cursor.getString(1))
                    val rawVars = cursor.getString(2)
                    put(
                        "variables",
                        try {
                            JSONObject(rawVars)
                        } catch (_: Throwable) {
                            rawVars
                        },
                    )
                    put("createdAt", isoFromMillis(cursor.getLong(3)))
                    put("attempts", cursor.getInt(4))
                    if (!cursor.isNull(5)) put("lastAttemptedAt", isoFromMillis(cursor.getLong(5)))
                    if (!cursor.isNull(6)) put("lastError", cursor.getString(6))
                }
                out.put(entry)
            }
        }
        return out.toString()
    }

    fun outboxClear() {
        writableDatabase.delete("outbox", null, null)
    }

    // ---- kv --------------------------------------------------------------

    fun kvSet(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "kv",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun kvGet(key: String): String? {
        readableDatabase.rawQuery(
            "SELECT value FROM kv WHERE key = ? LIMIT 1",
            arrayOf(key),
        ).use { cursor ->
            return if (cursor.moveToNext()) cursor.getString(0) else null
        }
    }

    fun kvDelete(key: String) {
        writableDatabase.delete("kv", "key = ?", arrayOf(key))
    }

    // ---- catalog (products + customers) ---------------------------------
    //
    // bulkUpsert(table, [{id, data, updatedAt, deletedAt?}]) — single
    // transaction so a 200-row pull is one fsync, not 200. List returns
    // non-deleted rows ordered by updated_at desc.

    fun catalogBulkUpsert(table: String, rowsJson: String): Int {
        require(table in CATALOG_TABLES) {
            "catalogBulkUpsert: unsupported table $table"
        }
        val rows = JSONArray(rowsJson)
        if (rows.length() == 0) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                val data = row.optJSONObject("data")?.toString() ?: row.optString("data")
                val updatedAt = row.optLong("updatedAt", now)
                val deletedAtRaw = row.opt("deletedAt")
                val values = ContentValues().apply {
                    put("id", id)
                    put("data", data)
                    put("updated_at", updatedAt)
                    if (deletedAtRaw is Number) {
                        put("deleted_at", deletedAtRaw.toLong())
                    } else {
                        putNull("deleted_at")
                    }
                    put("cached_at", now)
                }
                db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            return rows.length()
        } finally {
            db.endTransaction()
        }
    }

    fun catalogList(table: String, includeDeleted: Boolean = false): String {
        require(table in CATALOG_TABLES) {
            "catalogList: unsupported table $table"
        }
        val sql = buildString {
            append("SELECT id, data, updated_at, deleted_at FROM ")
            append(table)
            if (!includeDeleted) append(" WHERE deleted_at IS NULL")
            append(" ORDER BY updated_at DESC")
        }
        val out = JSONArray()
        readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", cursor.getString(0))
                    val rawData = cursor.getString(1)
                    put(
                        "data",
                        try {
                            JSONObject(rawData)
                        } catch (_: Throwable) {
                            rawData
                        },
                    )
                    put("updatedAt", cursor.getLong(2))
                    if (!cursor.isNull(3)) put("deletedAt", cursor.getLong(3))
                }
                out.put(obj)
            }
        }
        return out.toString()
    }

    fun catalogMaxUpdatedAt(table: String): Long {
        require(table in CATALOG_TABLES) {
            "catalogMaxUpdatedAt: unsupported table $table"
        }
        readableDatabase.rawQuery(
            "SELECT MAX(updated_at) FROM $table",
            null,
        ).use { cursor ->
            return if (cursor.moveToNext() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        }
    }

    fun catalogClear(table: String) {
        require(table in CATALOG_TABLES) {
            "catalogClear: unsupported table $table"
        }
        writableDatabase.delete(table, null, null)
    }

    // ---- sync_state ------------------------------------------------------

    fun syncStateGet(entity: String): String {
        readableDatabase.rawQuery(
            "SELECT cursor, last_pull_at, last_push_at FROM sync_state WHERE entity = ? LIMIT 1",
            arrayOf(entity),
        ).use { cursor ->
            if (!cursor.moveToNext()) return "{}"
            val obj = JSONObject().apply {
                if (!cursor.isNull(0)) put("cursor", cursor.getLong(0))
                if (!cursor.isNull(1)) put("lastPullAt", cursor.getLong(1))
                if (!cursor.isNull(2)) put("lastPushAt", cursor.getLong(2))
            }
            return obj.toString()
        }
    }

    fun syncStateSet(entity: String, cursor: Long?, lastPullAt: Long?, lastPushAt: Long?) {
        // Read-merge-write so callers can update one field without nuking
        // the others (e.g. push completes but no new cursor).
        val existing = JSONObject(syncStateGet(entity))
        val values = ContentValues().apply {
            put("entity", entity)
            if (cursor != null) put("cursor", cursor)
            else if (existing.has("cursor")) put("cursor", existing.getLong("cursor"))
            if (lastPullAt != null) put("last_pull_at", lastPullAt)
            else if (existing.has("lastPullAt")) put("last_pull_at", existing.getLong("lastPullAt"))
            if (lastPushAt != null) put("last_push_at", lastPushAt)
            else if (existing.has("lastPushAt")) put("last_push_at", existing.getLong("lastPushAt"))
        }
        writableDatabase.insertWithOnConflict(
            "sync_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- sync_log --------------------------------------------------------

    fun syncLogAppend(
        at: Long,
        direction: String,
        entity: String,
        refId: String?,
        cursor: Long?,
        count: Int?,
        status: String,
        error: String?,
        deviceId: String?,
        durationMs: Long?,
    ) {
        val values = ContentValues().apply {
            put("at", at)
            put("direction", direction)
            put("entity", entity)
            put("ref_id", refId)
            if (cursor != null) put("cursor", cursor)
            if (count != null) put("count", count)
            put("status", status)
            put("error", error)
            put("device_id", deviceId)
            if (durationMs != null) put("duration_ms", durationMs)
        }
        writableDatabase.insert("sync_log", null, values)
        // Rolling cap: keep ~5000 rows. Trim 500 at a time so we're not
        // running a DELETE on every single append.
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_log", null).use { cursor2 ->
            if (cursor2.moveToNext() && cursor2.getLong(0) > SYNC_LOG_CAP) {
                writableDatabase.execSQL(
                    "DELETE FROM sync_log WHERE id IN (SELECT id FROM sync_log ORDER BY id ASC LIMIT ?)",
                    arrayOf(SYNC_LOG_TRIM_BATCH),
                )
            }
        }
    }

    fun syncLogList(limit: Int): String {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, at, direction, entity, ref_id, cursor, count, status, error, device_id, duration_ms FROM sync_log ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("at", cursor.getLong(1))
                    put("direction", cursor.getString(2))
                    put("entity", cursor.getString(3))
                    if (!cursor.isNull(4)) put("refId", cursor.getString(4))
                    if (!cursor.isNull(5)) put("cursor", cursor.getLong(5))
                    if (!cursor.isNull(6)) put("count", cursor.getInt(6))
                    put("status", cursor.getString(7))
                    if (!cursor.isNull(8)) put("error", cursor.getString(8))
                    if (!cursor.isNull(9)) put("deviceId", cursor.getString(9))
                    if (!cursor.isNull(10)) put("durationMs", cursor.getLong(10))
                }
                out.put(obj)
            }
        }
        return out.toString()
    }

    private fun isoFromMillis(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).toString()

    companion object {
        private const val DB_NAME = "hdq.db"
        private const val DB_VERSION = 3
        private const val SYNC_LOG_CAP = 5000L
        private const val SYNC_LOG_TRIM_BATCH = 500L
        private val CATALOG_TABLES = setOf("products", "customers", "shifts", "invoices")

        @Volatile private var instance: Storage? = null

        fun get(context: Context): Storage {
            return instance ?: synchronized(this) {
                instance ?: Storage(context.applicationContext).also { instance = it }
            }
        }
    }
}
