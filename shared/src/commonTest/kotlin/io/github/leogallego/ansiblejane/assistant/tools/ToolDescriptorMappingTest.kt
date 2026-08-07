package io.github.leogallego.ansiblejane.assistant.tools

import ai.koog.agents.core.tools.ToolDescriptor
import io.github.leogallego.ansiblejane.assistant.data.TokenSavingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolDescriptorMappingTest {

    private fun listHostsSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("inventory_id", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Filter hosts by inventory ID"))
            })
            put("search", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search term to filter hosts by name"))
            })
            put("page", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Page number (default 1)"))
            })
            put("host_id", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Required host identifier"))
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("host_id"))
        })
    }

    private fun fixtureSpec() = ToolSpec(
        name = "list_hosts",
        description = "List hosts, optionally filtered by inventory ID or search term",
        parametersSchema = listHostsSchema()
    )

    private fun schemaChars(descriptor: ToolDescriptor): Int =
        descriptor.name.length + descriptor.description.length +
            descriptor.requiredParameters.sumOf { p -> p.name.length + p.description.length + 20 } +
            descriptor.optionalParameters.sumOf { p -> p.name.length + p.description.length + 20 }

    @Test
    fun `STANDARD SHOULD keep optional parameters as structured fields`() {
        val descriptor = fixtureSpec().toToolDescriptor(SchemaCompressionLevel.FULL)

        assertEquals(1, descriptor.requiredParameters.size)
        assertEquals("host_id", descriptor.requiredParameters.single().name)
        assertTrue(descriptor.optionalParameters.size >= 3)
        assertTrue(descriptor.optionalParameters.any { it.name == "inventory_id" })
        assertTrue(descriptor.optionalParameters.any { it.name == "search" })
    }

    @Test
    fun `TOKEN_SAVER SHOULD keep required params and fold optionals into description`() {
        val descriptor = fixtureSpec().toToolDescriptor(SchemaCompressionLevel.STRIPPED)

        assertEquals(1, descriptor.requiredParameters.size)
        assertEquals("host_id", descriptor.requiredParameters.single().name)
        assertTrue(
            descriptor.requiredParameters.single().description.contains("host"),
            "Required param description must not be gutted"
        )
        assertTrue(descriptor.optionalParameters.isEmpty())
        assertTrue(
            descriptor.description.contains("Optional:", ignoreCase = true),
            "Optional param names should be folded into description: ${descriptor.description}"
        )
        assertTrue(descriptor.description.contains("inventory_id"))
        assertTrue(descriptor.description.contains("search"))
        assertTrue(descriptor.description.contains("page"))
    }

    @Test
    fun `TOKEN_SAVER schemas SHOULD be smaller than STANDARD`() {
        val full = fixtureSpec().toToolDescriptor(SchemaCompressionLevel.FULL)
        val stripped = fixtureSpec().toToolDescriptor(SchemaCompressionLevel.STRIPPED)

        val fullChars = schemaChars(full)
        val strippedChars = schemaChars(stripped)
        assertTrue(
            strippedChars < fullChars,
            "STRIPPED ($strippedChars) should be smaller than FULL ($fullChars)"
        )
    }

    @Test
    fun `TokenSavingMode mapping SHOULD treat TOOLS_ONLY like TOKEN_SAVER for schemas`() {
        assertEquals(SchemaCompressionLevel.FULL, TokenSavingMode.STANDARD.toSchemaCompression())
        assertEquals(SchemaCompressionLevel.STRIPPED, TokenSavingMode.TOKEN_SAVER.toSchemaCompression())
        assertEquals(SchemaCompressionLevel.STRIPPED, TokenSavingMode.TOOLS_ONLY.toSchemaCompression())
    }

    @Test
    fun `default toToolDescriptor SHOULD remain FULL for backward compatibility`() {
        val descriptor = fixtureSpec().toToolDescriptor()
        assertTrue(descriptor.optionalParameters.isNotEmpty())
    }
}
