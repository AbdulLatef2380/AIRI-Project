package com.airi.assistant.memory

import androidx.room.TypeConverter
import com.airi.assistant.memory.entity.AuditLogEntity

/**
 * Room TypeConverters for [AuditLogEntity].
 *
 * Room cannot persist Kotlin enums directly — it needs explicit converters that
 * map between the enum value and a SQLite-storable primitive. We store Level as
 * its name (a String) so the audit_log table remains human-readable in DB
 * inspection tools without requiring schema knowledge.
 *
 * Registered on [AiriDatabase] via the `@TypeConverters(AuditLogTypeConverters::class)`
 * annotation so Room discovers them for the whole database.
 */
class AuditLogTypeConverters {

    @TypeConverter
    fun levelToString(level: AuditLogEntity.Level): String = level.name

    @TypeConverter
    fun stringToLevel(value: String): AuditLogEntity.Level =
        runCatching { AuditLogEntity.Level.valueOf(value) }
            .getOrDefault(AuditLogEntity.Level.INFO)
}
