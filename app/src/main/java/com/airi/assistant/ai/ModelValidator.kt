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
    object Unreadable : ValidationResult()
    object InvalidExtension : ValidationResult()
    object InvalidFormat : ValidationResult()
    object MalformedMetadata : ValidationResult()
    object TooSmall : ValidationResult()
    data class UnsupportedArchitecture(val architecture: String) : ValidationResult()
    data class InsufficientRam(val requiredMb: Long, val availableMb: Long) : ValidationResult()
}

object ModelValidator {
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private const val MIN_HEADER_BYTES = 24L
    private const val TAG = "AIRI_MODEL"

    data class Inspection(
        val fileSizeBytes: Long,
        val ggufVersion: Int,
        val architecture: String,
        val readable: Boolean
    )

    fun validate(file: File, context: Context?, ramRequiredMb: Int = 0): ValidationResult {
        if (!file.exists()) return ValidationResult.FileNotFound
        if (!file.isFile || !file.canRead()) return ValidationResult.Unreadable
        if (!file.name.lowercase().endsWith(".gguf")) return ValidationResult.InvalidExtension
        if (file.length() < MIN_HEADER_BYTES) return ValidationResult.TooSmall
        when (readHeader(file)) {
            HeaderResult.InvalidFormat -> return ValidationResult.InvalidFormat
            HeaderResult.Malformed -> return ValidationResult.MalformedMetadata
            is HeaderResult.Valid -> Unit
        }
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
        // mmap streams weights from storage, but llama.cpp still allocates
        // metadata, scratch buffers and KV cache. Keep enough headroom to fail
        // gracefully instead of allowing Android to kill the process mid-load.
        val estimate = kotlin.math.ceil(fileSizeMb * 1.40 + 768.0)
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

    private sealed class HeaderResult {
        data class Valid(val version: Int, val tensorCount: Long, val metadataCount: Long) : HeaderResult()
        object InvalidFormat : HeaderResult()
        object Malformed : HeaderResult()
    }

    private fun readHeader(file: File): HeaderResult {
        return try {
            val header = ByteArray(24)
            file.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read <= 0) return HeaderResult.Malformed
                    offset += read
                }
            }
            if (!header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC)) {
                return HeaderResult.InvalidFormat
            }
            val version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (version !in 2..3) return HeaderResult.InvalidFormat
            val tensorCount = ByteBuffer.wrap(header, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val metadataCount = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
            // Reject impossible counts before llama.cpp parses attacker-controlled
            // lengths. These bounds cover practical models and avoid integer or
            // allocation hazards from malformed/truncated files.
            if (tensorCount !in 1..1_000_000L || metadataCount !in 0..1_000_000L) {
                return HeaderResult.Malformed
            }
            HeaderResult.Valid(version, tensorCount, metadataCount)
        } catch (e: Exception) {
            Log.w(TAG, "GGUF_HEADER_READ_FAILED path=${file.absolutePath} reason=${e.message}")
            HeaderResult.Malformed
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
