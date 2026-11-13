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
    fun rejectsValidLookingContentWithUnsupportedExtension() {
        val file = fixture(version = 3, size = 64, extension = ".txt")
        assertEquals(ValidationResult.InvalidExtension, ModelValidator.validate(file, null, 0))
    }

    @Test
    fun rejectsTruncatedGgufHeaderAsTooSmall() {
        val file = fixture(version = 3, size = 8)
        assertEquals(ValidationResult.TooSmall, ModelValidator.validate(file, null, 0))
    }

    @Test
    fun rejectsImpossibleGgufCountsAsMalformedMetadata() {
        val file = fixture(version = 3, size = 64, tensorCount = 0L)
        assertEquals(ValidationResult.MalformedMetadata, ModelValidator.validate(file, null, 0))
    }

    @Test
    fun rejectsUnsupportedGgufVersionBeforeNativeLoad() {
        val file = fixture(version = 1, size = 24)
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
    fun marksUnknownArchitectureAsNotVerifiedInsteadOfCallingItUnsupported() {
        val file = fixture(version = 3, size = 64, architecture = null)

        assertEquals(
            ValidationResult.UnknownArchitecture("unknown"),
            ModelValidator.validate(file, null, 0)
        )
    }

    @Test
    fun customModelRamEstimateIncludesWeightAndRuntimeHeadroom() {
        assertEquals(512, ModelValidator.estimateRequiredRamMb(0))
        assertEquals(
            1485,
            ModelValidator.estimateRequiredRamMb(512L * 1024L * 1024L)
        )
    }

    private fun fixture(
        version: Int,
        size: Int,
        architecture: String? = "qwen2",
        extension: String = ".gguf",
        tensorCount: Long = 1L
    ): File {
        val file = File.createTempFile("airi-model-", extension)
        files += file
        val bytes = ByteArray(size)
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(version)
        if (size >= 24) {
            ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(tensorCount)
            ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L)
        }
        architecture?.takeIf { size > 24 }?.let { modelArchitecture ->
            val metadataTag = "general.architecture $modelArchitecture".toByteArray(Charsets.US_ASCII)
            metadataTag.copyInto(bytes, destinationOffset = minOf(24, size), endIndex = minOf(metadataTag.size, size - 24))
        }
        file.writeBytes(bytes)
        return file
    }
}
