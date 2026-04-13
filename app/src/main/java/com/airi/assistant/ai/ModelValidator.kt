package com.airi.assistant.ai

import android.app.ActivityManager
import android.content.Context
import java.io.File

sealed class ValidationResult {
    object Valid : ValidationResult()
    object FileNotFound : ValidationResult()
    object InvalidFormat : ValidationResult()
    object TooSmall : ValidationResult()
    data class InsufficientRam(val requiredMb: Long, val availableMb: Long) : ValidationResult()
}

object ModelValidator {
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private const val MIN_SIZE_BYTES = 50L * 1024 * 1024

    fun validate(file: File, context: Context, ramRequiredMb: Int = 0): ValidationResult {
        if (!file.exists()) return ValidationResult.FileNotFound
        if (file.length() < MIN_SIZE_BYTES) return ValidationResult.TooSmall
        if (!hasGgufHeader(file)) return ValidationResult.InvalidFormat
        if (ramRequiredMb > 0) {
            val availableMb = getAvailableRamMb(context)
            if (availableMb < ramRequiredMb) {
                return ValidationResult.InsufficientRam(ramRequiredMb.toLong(), availableMb)
            }
        }
        return ValidationResult.Valid
    }

    private fun hasGgufHeader(file: File): Boolean {
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header.contentEquals(GGUF_MAGIC)
        } catch (e: Exception) {
            false
        }
    }

    private fun getAvailableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024L * 1024L)
    }
}
