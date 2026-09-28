package com.airi.assistant.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test

class ModelValidatorTest {
    private val files = mutableListOf<File>()

    @After
    fun cleanup() {
        files.forEach { it.delete() }
    }

    @Test
    fun acceptsSmallGgufHeaderWithSupportedVersion() {
        val file = fixture(version = 3, size = 64)
        assertEquals(ValidationResult.Valid, ModelValidator.validate(file, null, 0))
    }

    @Test
    fun rejectsUnsupportedGgufVersionBeforeNativeLoad() {
        val file = fixture(version = 1, size = 16)
        assertEquals(ValidationResult.InvalidFormat, ModelValidator.validate(file, null, 0))
    }

    @Test
    fun rejectsKnownNativeUnsupportedArchitectureBeforeLoad() {
        val file = fixture(version = 3, size = 64, architecture = "mistral")

        assertEquals(
            ValidationResult.UnsupportedArchitecture("mistral"),
            ModelValidator.validate(file, null, 0)
        )
    }

    @Test
    fun rejectsUnknownArchitectureInsteadOfPassingItToNativeLoader() {
        val file = fixture(version = 3, size = 64, architecture = null)

        assertEquals(
            ValidationResult.UnsupportedArchitecture("unknown"),
            ModelValidator.validate(file, null, 0)
        )
    }

    @Test
    fun customModelRamEstimateIncludesWeightAndRuntimeHeadroom() {
        assertEquals(512, ModelValidator.estimateRequiredRamMb(0))
        assertEquals(
            1152,
            ModelValidator.estimateRequiredRamMb(512L * 1024L * 1024L)
        )
    }

    private fun fixture(version: Int, size: Int, architecture: String? = "qwen2"): File {
        val file = File.createTempFile("airi-model-", ".gguf")
        files += file
        val bytes = ByteArray(size)
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(version)
        architecture?.let { modelArchitecture ->
            val metadataTag = "general.architecture $modelArchitecture".toByteArray(Charsets.US_ASCII)
            metadataTag.copyInto(bytes, destinationOffset = 8, endIndex = minOf(metadataTag.size, size - 8))
        }
        file.writeBytes(bytes)
        return file
    }
}
