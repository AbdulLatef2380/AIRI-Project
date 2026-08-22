package com.airi.assistant.developer.database

import androidx.sqlite.db.SimpleSQLiteQuery
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only inspector for AIRI's Room database.
 *
 * Results are bounded for UI safety. This manager intentionally has no write,
 * migration, backup, or arbitrary pragma API; those operations require a
 * separate approval-backed contract.
 */
class DatabaseLabManager(
    private val database: AiriDatabase,
    private val auditRepository: AuditRepository
) {
    data class QueryResult(
        val columns: List<String>,
        val rows: List<List<String>>,
        val truncated: Boolean,
        val elapsedMs: Long
    )

    sealed class Execution {
        data class Success(val result: QueryResult) : Execution()
        data class Rejected(val reason: String) : Execution()
        data class Failed(val reason: String) : Execution()
    }

    suspend fun executeReadOnly(sql: String): Execution = withContext(Dispatchers.IO) {
        val accepted = DatabaseLabQueryPolicy.evaluate(sql)
        if (accepted is DatabaseLabQueryPolicy.Decision.Rejected) {
            auditRepository.warn("DATABASE_LAB", "read_query_rejected reason=${accepted.reason.take(96)}")
            return@withContext Execution.Rejected(accepted.reason)
        }
        val query = (accepted as DatabaseLabQueryPolicy.Decision.Allowed).normalizedSql
        val startedAt = System.currentTimeMillis()
        runCatching {
            database.openHelper.readableDatabase.query(SimpleSQLiteQuery(query)).use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = mutableListOf<List<String>>()
                var truncated = false
                while (cursor.moveToNext()) {
                    if (rows.size >= DatabaseLabQueryPolicy.MAX_RESULT_ROWS) {
                        truncated = true
                        break
                    }
                    rows += columns.indices.map { index -> cursorValue(cursor, index) }
                }
                QueryResult(
                    columns = columns,
                    rows = rows,
                    truncated = truncated,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )
            }
        }.fold(
            onSuccess = { result ->
                auditRepository.info(
                    "DATABASE_LAB",
                    "read_query_completed chars=${query.length} rows=${result.rows.size} truncated=${result.truncated} elapsedMs=${result.elapsedMs}"
                )
                Execution.Success(result)
            },
            onFailure = { error ->
                auditRepository.warn(
                    "DATABASE_LAB",
                    "read_query_failed chars=${query.length} error=${error.javaClass.simpleName}"
                )
                Execution.Failed("Query could not be completed")
            }
        )
    }

    private fun cursorValue(cursor: android.database.Cursor, index: Int): String = when (cursor.getType(index)) {
        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
        android.database.Cursor.FIELD_TYPE_BLOB -> "<${cursor.getBlob(index).size} bytes>"
        else -> cursor.getString(index).orEmpty().take(MAX_CELL_CHARS)
    }

    private companion object {
        const val MAX_CELL_CHARS = 1_000
    }
}
