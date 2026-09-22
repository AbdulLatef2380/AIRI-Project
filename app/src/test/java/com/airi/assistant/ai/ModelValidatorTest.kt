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
        val file = fixture(version = 3, size = 16)
        assertEquals(ValidationResult.Valid, ModelValidator.validate(file, null, 0))
    }

    @Test
    fun rejectsUnsupportedGgufVersionBeforeNativeLoad() {
        val file = fixture(version = 1, size = 16)
        assertEquals(ValidationResult.InvalidFormat, ModelValidator.validate(file, null, 0))
    }

    private fun fixture(version: Int, size: Int): File {
        val file = File.createTempFile("airi-model-", ".gguf")
        files += file
        val bytes = ByteArray(size)
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(version)
        file.writeBytes(bytes)
        return file
    }
}
