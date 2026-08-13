package com.airi.assistant.memory

import android.content.Context
import android.util.Log
import java.io.File
import net.sqlcipher.database.SQLiteDatabase as SqlCipherDatabase

/**
 * AP-02: SQLCipher at-rest encryption migration helper.
 *
 * Converts an existing **plaintext** SQLite database (`airi_memory_db`) to an
 * **encrypted** SQLCipher database using the ATTACH → sqlcipher_export → DETACH
 * pattern. This is the only safe way to encrypt an existing SQLite database
 * without losing data.
 *
 * ## Migration algorithm
 * ```
 * 1. Open the plaintext DB (raw SQLiteDatabase — NOT Room).
 * 2. ATTACH an empty encrypted DB at a temp path.
 * 3. sqlcipher_export('enc') — copies all tables, indices, views.
 * 4. DETACH encrypted DB.
 * 5. Delete original plaintext file.
 * 6. Rename encrypted temp file to the original path.
 * ```
 *
 * ## Safety guarantees
 * - Idempotent: if the plaintext file no longer exists (already migrated),
 *   returns immediately without touching anything.
 * - Magic-byte check: if the file does not start with "SQLite format 3\000"
 *   it is already encrypted — returns immediately (no-op).
 * - Atomic rename: the temp encrypted file replaces the original only after a
 *   successful export. On failure the temp file is deleted; the original is
 *   untouched. The next launch will retry.
 * - WAL cleanup: SHM and WAL sidecar files are removed after migration to
 *   avoid Room opening a partially-consistent WAL on the encrypted DB.
 *
 * ## Caller contract
 * Call [migrateIfNeeded] **before** `Room.databaseBuilder(...).build()`.
 * The Room builder must also set the matching [SupportFactory] via
 * [AiriDatabase.ENCRYPTION_ENABLED].
 */
object AiriDatabaseMigrationHelper {

    private const val TAG     = "AiriDbMigration"
    private const val DB_NAME = "airi_memory_db"

    // SQLite plaintext magic: first 16 bytes are "SQLite format 3\000"
    private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.US_ASCII)

    /**
     * Migrate [DB_NAME] from plaintext to SQLCipher-encrypted if the plaintext
     * file is present and the magic bytes confirm it is unencrypted.
     *
     * @param context   Application context.
     * @param encKey    The passphrase used for encryption (must match what
     *                  [SupportFactory] will use when Room opens the DB).
     */
    fun migrateIfNeeded(context: Context, encKey: String) {
        val dbFile  = context.getDatabasePath(DB_NAME)
        val encFile = File(dbFile.absolutePath + ".cipher_tmp")
        val backupFile = File(dbFile.absolutePath + ".plaintext_backup")

        // ── Guard 1: no plaintext file → already migrated or first launch ────
        if (!dbFile.exists()) {
            Log.i(TAG, "AP-02 migrateIfNeeded: no plaintext DB found — skipping (first run or already encrypted)")
            return
        }

        // ── Guard 2: magic-byte check — is it already encrypted? ─────────────
        if (!isPlaintextSQLite(dbFile)) {
            Log.i(TAG, "AP-02 migrateIfNeeded: DB magic bytes indicate it is already encrypted — skipping")
            return
        }

        Log.i(TAG, "AP-02 migrateIfNeeded: plaintext DB detected — starting SQLCipher migration")

        // Clean up artifacts from a previous failed attempt. The original
        // database is never deleted until its encrypted replacement is in place.
        encFile.delete()
        backupFile.delete()

        try {
            // ── Step 1: open plaintext DB with SQLCipher in "plain" mode ─────
            // net.zetetic.database.sqlcipher.SQLiteDatabase supports opening
            // unencrypted databases when the passphrase is empty byte array.
            val sqliteDb = SqlCipherDatabase.openDatabase(
                dbFile.absolutePath,
                "",             // empty passphrase = open as plaintext (old API takes String)
                null,           // CursorFactory
                SqlCipherDatabase.OPEN_READWRITE
            )

            // Avoid .use{} — old net.sqlcipher SQLiteClosable may not implement Closeable.
            try {
                // Escape single quotes in the key (paranoid safety)
                val escapedKey = encKey.replace("'", "''")
                val escapedPath = encFile.absolutePath.replace("'", "''")

                // ── Step 2+3+4: ATTACH → export → DETACH ─────────────────────
                sqliteDb.rawExecSQL("ATTACH DATABASE '$escapedPath' AS enc KEY '$escapedKey'")
                sqliteDb.rawExecSQL("SELECT sqlcipher_export('enc')")
                sqliteDb.rawExecSQL("DETACH DATABASE enc")
            } finally {
                sqliteDb.close()
            }

            // ── Step 5+6: replace with rollback protection ────────────────────
            if (!encFile.exists() || encFile.length() == 0L) {
                Log.e(TAG, "SQLCipher migration did not create an encrypted database")
                return
            }
            if (!dbFile.renameTo(backupFile)) {
                Log.e(TAG, "SQLCipher migration could not preserve the plaintext database")
                return
            }
            if (!encFile.renameTo(dbFile)) {
                val restored = backupFile.renameTo(dbFile)
                Log.e(TAG, "SQLCipher migration replacement failed; plaintext restored=$restored")
                return
            }
            backupFile.delete()

            // Remove stale sidecars only after the encrypted database is in place.
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()

            Log.i(TAG, "AIRI_RUNTIME DB_ENCRYPTED migration successful size=${dbFile.length()}")

        } catch (e: Exception) {
            Log.e(TAG, "AP-02 migrateIfNeeded: migration FAILED — ${e.message}. " +
                "Plaintext DB preserved for retry.", e)
            // Clean up the temporary database. The original plaintext database
            // remains in place unless the guarded replacement completed.
            encFile.delete()
            if (backupFile.exists() && !dbFile.exists()) backupFile.renameTo(dbFile)
            // Do NOT rethrow — let Room open the existing plaintext DB rather
            // than crashing the app. Encryption will retry on next launch.
        }
    }

    /** Returns true if [file] starts with the SQLite plaintext magic header. */
    private fun isPlaintextSQLite(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(SQLITE_MAGIC.size)
                val read = stream.read(header)
                read == SQLITE_MAGIC.size && header.contentEquals(SQLITE_MAGIC)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AP-02 isPlaintextSQLite: cannot read header — ${e.message}")
            false
        }
    }
}
