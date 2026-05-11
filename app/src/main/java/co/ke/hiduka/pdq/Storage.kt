package co.ke.hiduka.pdq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native SQLite store that mirrors the PWA's critical IndexedDB state.
 *
 * Two tables:
 *  - `outbox` — queued GraphQL mutations awaiting replay (today: sale
 *    invoices; tomorrow: product creates, shift events, customer creates).
 *  - `kv` — small key-value store for cached user, JWT, and any other
 *    bootstrap state that needs to survive a WebView storage wipe.
 *
 * Why this exists: some Android variants will aggressively evict
 * WebView IndexedDB under memory pressure. If a cashier rings up a sale
 * offline, IDB stores the outbox entry, the OS kills the WebView before
 * the network returns, IDB is gone → sale is lost. Mirroring those
 * writes to SQLite means the PWA can restore the queue on next launch
 * (or a WorkManager job can replay it without the PWA being open).
 *
 * The PWA stays authoritative when both stores exist; SQLite is the
 * fallback. All access is synchronous — SQLite is fast and the bridge
 * is called from the WebView JS thread which can afford the few-ms hop.
 */
class Storage(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE outbox (
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
        db.execSQL("CREATE INDEX idx_outbox_created_at ON outbox(created_at)")
        db.execSQL(
            """
            CREATE TABLE kv (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No upgrades yet. When the schema grows, add ALTER TABLE migrations
        // here keyed off oldVersion → newVersion.
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
        // SQLite's UPDATE … attempts = attempts + 1 is cheaper than read/modify/write.
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
                    // Variables are stored as a JSON string; decode so the
                    // PWA sees an object, not a stringified object.
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

    private fun isoFromMillis(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).toString()

    companion object {
        private const val DB_NAME = "hdq.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: Storage? = null

        fun get(context: Context): Storage {
            return instance ?: synchronized(this) {
                instance ?: Storage(context.applicationContext).also { instance = it }
            }
        }
    }
}
