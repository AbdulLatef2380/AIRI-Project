package com.airi.assistant.developer.database

/**
 * Read-only SQL admission boundary for the on-device developer database lab.
 * It deliberately supports a small SQL subset rather than attempting to
 * sandbox arbitrary SQLite grammar.
 */
object DatabaseLabQueryPolicy {
    const val MAX_QUERY_CHARS = 2_000
    const val MAX_RESULT_ROWS = 100

    sealed class Decision {
        data class Allowed(val normalizedSql: String) : Decision()
        data class Rejected(val reason: String) : Decision()
    }

    fun evaluate(sql: String): Decision {
        val normalized = sql.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return Decision.Rejected("Enter a read-only SQL query")
        if (normalized.length > MAX_QUERY_CHARS) return Decision.Rejected("Query exceeds $MAX_QUERY_CHARS characters")
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*") || normalized.contains("*/")) {
            return Decision.Rejected("Comments and multiple SQL statements are not allowed")
        }
        val lowered = normalized.lowercase()
        val forbidden = listOf(
            "insert", "update", "delete", "replace", "drop", "alter", "create",
            "attach", "detach", "vacuum", "reindex", "transaction", "begin", "commit",
            "rollback", "savepoint", "release", "pragma writable_schema"
        )
        if (forbidden.any { Regex("\\b$it\\b").containsMatchIn(lowered) }) {
            return Decision.Rejected("Only read-only schema and SELECT queries are allowed")
        }
        if (lowered.startsWith("select ") || lowered.startsWith("explain query plan select ")) {
            return Decision.Allowed(normalized)
        }
        val allowedPragmas = listOf("pragma table_info(", "pragma index_list(", "pragma index_info(", "pragma database_list")
        if (allowedPragmas.any { lowered.startsWith(it) }) return Decision.Allowed(normalized)
        return Decision.Rejected("Start with SELECT, EXPLAIN QUERY PLAN SELECT, or an allowed schema PRAGMA")
    }
}
