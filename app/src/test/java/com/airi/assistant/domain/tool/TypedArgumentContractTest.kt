package com.airi.assistant.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedArgumentContractTest {
    @Test
    fun validates_scalars_default_and_nullable_values() {
        val specs = listOf(
            TypedArgumentSpec("count", TypedArgumentSpec.Type.INTEGER, required = true, minInt = 1, maxInt = 10),
            TypedArgumentSpec("enabled", TypedArgumentSpec.Type.BOOLEAN, defaultValue = "true"),
            TypedArgumentSpec("note", TypedArgumentSpec.Type.STRING, nullable = true),
        )
        val result = TypedArgumentContract.validate(specs, mapOf("count" to "3"))
        assertTrue(result.isValid)
        assertEquals("3", result.value!!.canonical["count"])
        assertEquals("true", result.value!!.canonical["enabled"])
        assertEquals("null", result.value!!.canonical["note"])
    }

    @Test
    fun validates_nested_object_and_list() {
        val item = TypedArgumentSpec("item", TypedArgumentSpec.Type.STRING, required = true)
        val specs = listOf(
            TypedArgumentSpec("payload", TypedArgumentSpec.Type.OBJECT, required = true, properties = mapOf("name" to TypedArgumentSpec("name", TypedArgumentSpec.Type.STRING, required = true))),
            TypedArgumentSpec("items", TypedArgumentSpec.Type.LIST, required = true, items = item),
        )
        val result = TypedArgumentContract.validate(specs, mapOf("payload" to "{\"name\":\"AIRI\"}", "items" to "[\"one\",\"two\"]"))
        assertTrue(result.isValid)
        assertTrue(result.value!!.values["payload"] is TypedArgumentContract.Value.ObjectValue)
        assertTrue(result.value!!.values["items"] is TypedArgumentContract.Value.ListValue)
    }

    @Test
    fun rejects_unknown_missing_and_wrong_type_with_repair_hint() {
        val spec = listOf(TypedArgumentSpec("count", TypedArgumentSpec.Type.INTEGER, required = true))
        val unknown = TypedArgumentContract.validate(spec, mapOf("other" to "1"))
        assertFalse(unknown.isValid)
        assertEquals("unknown_argument", unknown.failure!!.code)
        assertTrue(unknown.failure!!.repairHint.isNotBlank())
        val wrong = TypedArgumentContract.validate(spec, mapOf("count" to "1.5"))
        assertFalse(wrong.isValid)
        assertEquals("invalid_argument", wrong.failure!!.code)
        val missing = TypedArgumentContract.validate(spec, emptyMap())
        assertEquals("missing_argument", missing.failure!!.code)
    }
}
