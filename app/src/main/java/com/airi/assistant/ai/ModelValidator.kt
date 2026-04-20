package com.airi.assistant.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class ValidationResult {
    object Valid : ValidationResult()
    object FileNotFound : ValidationResult()
    object InvalidFormat : ValidationResult()
    object TooSmall : ValidationResult()
    data class InsufficientRam(val requiredMb: Long, val availableMb: Long) : ValidationResult()
}

object ModelValidator {
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private const val MIN_SIZE_BYTES = 100_000_000L
    private const val TAG = "AIRI_MODEL"

    data class Inspection(
        val fileSizeBytes: Long,
        val ggufVersion: Int,
        val architecture: String,
        val readable: Boolean
    )

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

    fun inspect(file: File): Inspection {
        val version = readGgufVersion(file)
        val architecture = detectArchitecture(file)
        val readable = file.canRead()
        Log.i(TAG, "GGUF_INSPECT path=${file.absolutePath} size=${file.length()} version=$version architecture=$architecture readable=$readable")
        return Inspection(file.length(), version, architecture, readable)
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

    private fun readGgufVersion(file: File): Int {
        return try {
            val header = ByteArray(8)
            file.inputStream().use { input ->
                val read = input.read(header)
                if (read < 8 || !header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC)) return -1
            }
            ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } catch (e: Exception) {
            -1
        }
    }

    private fun detectArchitecture(file: File): String {
        return try {
            val sample = ByteArray(minOf(file.length(), 1024L * 1024L).toInt())
            file.inputStream().use { it.read(sample) }
            val printable = sample.map { b ->
                val value = b.toInt() and 0xFF
                if (value in 32..126) value.toChar() else ' '
            }.joinToString("")
            when {
                Regex("gemma2?", RegexOption.IGNORE_CASE).containsMatchIn(printable) -> "gemma"
                Regex("qwen2?", RegexOption.IGNORE_CASE).containsMatchIn(printable) -> "qwen"
                Regex("llama", RegexOption.IGNORE_CASE).containsMatchIn(printable) -> "llama"
                Regex("mistral|mixtral", RegexOption.IGNORE_CASE).containsMatchIn(printable) -> "mistral"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getAvailableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024L * 1024L)
    }
}
