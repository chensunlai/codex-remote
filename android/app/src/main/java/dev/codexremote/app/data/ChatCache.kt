package dev.codexremote.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

class ChatCache(context: Context) : SQLiteOpenHelper(context, DATABASE, null, VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE thread_cache (
                scope TEXT NOT NULL,
                service_id TEXT NOT NULL,
                thread_id TEXT NOT NULL,
                remote_updated INTEGER NOT NULL,
                payload TEXT NOT NULL,
                accessed INTEGER NOT NULL,
                PRIMARY KEY (scope, service_id, thread_id)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX thread_cache_accessed ON thread_cache(accessed)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        database.execSQL("DROP TABLE IF EXISTS thread_cache")
        onCreate(database)
    }

    suspend fun get(
        scope: String,
        serviceId: String,
        threadId: String,
        remoteUpdated: Long,
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (remoteUpdated <= 0) return@withContext null
        readableDatabase.query(
            "thread_cache",
            arrayOf("remote_updated", "payload"),
            "scope = ? AND service_id = ? AND thread_id = ?",
            arrayOf(scope, serviceId, threadId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getLong(0) < remoteUpdated) return@withContext null
            val payload = runCatching { JSONObject(cursor.getString(1)) }.getOrNull()
                ?: return@withContext null
            writableDatabase.execSQL(
                """
                UPDATE thread_cache SET accessed = ?
                WHERE scope = ? AND service_id = ? AND thread_id = ?
                """.trimIndent(),
                arrayOf<Any>(System.currentTimeMillis(), scope, serviceId, threadId),
            )
            payload
        }
    }

    suspend fun put(
        scope: String,
        serviceId: String,
        threadId: String,
        remoteUpdated: Long,
        payload: JSONObject,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("scope", scope)
            put("service_id", serviceId)
            put("thread_id", threadId)
            put("remote_updated", remoteUpdated)
            put("payload", payload.toString())
            put("accessed", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "thread_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        prune(writableDatabase)
    }

    suspend fun delete(scope: String, serviceId: String, threadId: String? = null) =
        withContext(Dispatchers.IO) {
            if (threadId == null) {
                writableDatabase.delete(
                    "thread_cache",
                    "scope = ? AND service_id = ?",
                    arrayOf(scope, serviceId),
                )
            } else {
                writableDatabase.delete(
                    "thread_cache",
                    "scope = ? AND service_id = ? AND thread_id = ?",
                    arrayOf(scope, serviceId, threadId),
                )
            }
        }

    private fun prune(database: SQLiteDatabase) {
        database.execSQL(
            """
            DELETE FROM thread_cache
            WHERE rowid IN (
                SELECT rowid FROM thread_cache
                ORDER BY accessed DESC
                LIMIT -1 OFFSET $MAX_THREADS
            )
            """.trimIndent(),
        )
        var bytes = DatabaseUtils.longForQuery(
            database,
            "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM thread_cache",
            null,
        )
        while (bytes > MAX_BYTES) {
            database.execSQL(
                """
                DELETE FROM thread_cache
                WHERE rowid IN (
                    SELECT rowid FROM thread_cache ORDER BY accessed ASC LIMIT 20
                )
                """.trimIndent(),
            )
            bytes = DatabaseUtils.longForQuery(
                database,
                "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM thread_cache",
                null,
            )
        }
    }

    companion object {
        fun scope(config: GatewayConfig): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((config.baseUrl + "\u0000" + config.token).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private const val DATABASE = "chat-cache.db"
        private const val VERSION = 1
        private const val MAX_THREADS = 200
        private const val MAX_BYTES = 50L * 1024 * 1024
    }
}
