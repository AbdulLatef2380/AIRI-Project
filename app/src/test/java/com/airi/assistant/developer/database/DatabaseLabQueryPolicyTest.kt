package com.airi.assistant.developer.database

import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseLabQueryPolicyTest {

    @Test
    fun permitsReadOnlyQueriesAndSchemaInspection() {
        assertAllowed("SELECT id, content FROM episodic_memory")
        assertAllowed("EXPLAIN QUERY PLAN SELECT * FROM chat_sessions")
        assertAllowed("PRAGMA table_info(episodic_memory)")
        assertAllowed("PRAGMA database_list")
    }

    @Test
    fun rejectsMutationsAndMultipleStatements() {
        assertRejected("DELETE FROM episodic_memory")
        assertRejected("SELECT * FROM audit_log; DELETE FROM audit_log")
        assertRejected("UPDATE chat_sessions SET title = 'x'")
        assertRejected("PRAGMA journal_mode = WAL")
    }

    @Test
    fun rejectsCommentsAndOversizedInput() {
        assertRejected("SELECT * FROM audit_log -- hide another statement")
        assertRejected("SELECT " + "x".repeat(DatabaseLabQueryPolicy.MAX_QUERY_CHARS))
    }

    private fun assertAllowed(sql: String) {
        assertTrue(DatabaseLabQueryPolicy.evaluate(sql) is DatabaseLabQueryPolicy.Decision.Allowed)
    }

    private fun assertRejected(sql: String) {
        assertTrue(DatabaseLabQueryPolicy.evaluate(sql) is DatabaseLabQueryPolicy.Decision.Rejected)
    }
}
