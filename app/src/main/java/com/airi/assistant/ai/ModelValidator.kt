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
    data class UnsupportedArchitecture(val architecture: String) : ValidationResult()
    data class InsufficientRam(val requiredMb: Long, val availableMb: Long) : ValidationResult()
}

object ModelValidator {
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private const val MIN_HEADER_BYTES = 8L
    private const val TAG = "AIRI_MODEL"

    data class Inspection(
        val fileSizeBytes: Long,
        val ggufVersion: Int,
        val architecture: String,
        val readable: Boolean
    )

    fun validate(file: File, context: Context?, ramRequiredMb: Int = 0): ValidationResult {
        if (!file.exists()) return ValidationResult.FileNotFound
        if (file.length() < MIN_HEADER_BYTES) return ValidationResult.TooSmall
        if (!hasSupportedGgufHeader(file)) return ValidationResult.InvalidFormat
        val architecture = detectArchitecture(file)
        if (architecture !in SUPPORTED_ARCHS) {
            return ValidationResult.UnsupportedArchitecture(architecture)
        }
        if (ramRequiredMb > 0) {
            val availableMb = context?.let(::getAvailableRamMb) ?: Long.MAX_VALUE
            if (availableMb < ramRequiredMb) {
                return ValidationResult.InsufficientRam(ramRequiredMb.toLong(), availableMb)
            }
        }
        return ValidationResult.Valid
    }

    /** Conservative estimate used only when a local model has no catalog RAM metadata. */
    fun estimateRequiredRamMb(fileSizeBytes: Long): Int {
        val fileSizeMb = fileSizeBytes.coerceAtLeast(0L).toDouble() / (1024.0 * 1024.0)
        val estimate = kotlin.math.ceil(fileSizeMb * 1.25 + 512.0)
        return estimate.coerceIn(512.0, Int.MAX_VALUE.toDouble()).toInt()
    }

    fun inspect(file: File): Inspection {
        val version = readGgufVersion(file)
        val architecture = detectArchitecture(file)
        val readable = file.canRead()
        Log.i(TAG, "GGUF_INSPECT path=${file.absolutePath} size=${file.length()} version=$version architecture=$architecture readable=$readable")
        Log.i("AIRI", "MODEL_TYPE_DETECTED architecture=$architecture ggufVersion=$version sizeBytes=${file.length()} path=${file.absolutePath}")
        return Inspection(file.length(), version, architecture, readable)
    }

    private fun hasSupportedGgufHeader(file: File): Boolean {
        return try {
            val header = ByteArray(8)
            file.inputStream().use { it.read(header) }
            if (!header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC)) return false
            val version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            // GGUF v2 and v3 are the versions supported by current llama.cpp.
            version == 2 || version == 3
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
            val architectureKey = "general.architecture"
            val keyIndex = printable.indexOf(architectureKey)
            val candidate = if (keyIndex >= 0) {
                printable.substring(keyIndex + architectureKey.length).take(128)
            } else {
                printable
            }
            Regex(
                "\\b(qwen3vl|qwen2vl|qwen3|qwen2|qwen|llama4|llama|gemma3|gemma2|gemma|mixtral|mistral|phi3|deepseek2?|starcoder2|command-r)\\b",
                RegexOption.IGNORE_CASE
            ).find(candidate)?.value?.lowercase() ?: "unknown"
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
