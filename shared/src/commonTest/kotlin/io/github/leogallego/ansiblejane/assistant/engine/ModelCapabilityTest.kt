package io.github.leogallego.ansiblejane.assistant.engine

import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.TokenSavingMode
import io.github.leogallego.ansiblejane.assistant.tools.SchemaCompressionLevel
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import io.github.leogallego.ansiblejane.assistant.tools.toSchemaCompression
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCapabilityTest {

    @Test
    fun `frontier providers SHOULD resolve to Full`() {
        assertEquals(
            ModelCapability.Full,
            ModelCapabilityResolver.resolve(KnownProvider.OPENAI, "gpt-4.1")
        )
        assertEquals(
            ModelCapability.Full,
            ModelCapabilityResolver.resolve(KnownProvider.GOOGLE_GEMINI, "gemini-2.5-flash")
        )
        assertEquals(
            ModelCapability.Full,
            ModelCapabilityResolver.resolve(KnownProvider.OPENROUTER, "anthropic/claude-sonnet-4")
        )
        assertEquals(
            ModelCapability.Full,
            ModelCapabilityResolver.resolve(KnownProvider.GROQ, "llama-3.3-70b-versatile")
        )
    }

    @Test
    fun `unknown small Ollama model SHOULD resolve to Simple`() {
        assertEquals(
            ModelCapability.Simple,
            ModelCapabilityResolver.resolve(KnownProvider.OLLAMA, "llama3.1:8b")
        )
        assertEquals(
            ModelCapability.Simple,
            ModelCapabilityResolver.resolve(KnownProvider.OLLAMA, "phi3:mini")
        )
        assertEquals(
            ModelCapability.Simple,
            ModelCapabilityResolver.resolve(KnownProvider.OLLAMA, "mystery-model")
        )
        assertEquals(
            ModelCapability.Simple,
            ModelCapabilityResolver.resolve(KnownProvider.CUSTOM, "")
        )
    }

    @Test
    fun `large self-hosted model SHOULD resolve to Full`() {
        assertEquals(
            ModelCapability.Full,
            ModelCapabilityResolver.resolve(KnownProvider.OLLAMA, "llama3.1:70b")
        )
        assertEquals(
            ModelCapability.Full,
            ModelCapabilityResolver.resolve(KnownProvider.ABBENAY, "qwen2.5:72b-instruct")
        )
    }

    @Test
    fun `onDevice flag SHOULD force Simple`() {
        assertEquals(
            ModelCapability.Simple,
            ModelCapabilityResolver.resolve(
                KnownProvider.OPENAI,
                "gpt-4.1",
                onDevice = true
            )
        )
    }

    @Test
    fun `Simple effective mode SHOULD force TOOLS_ONLY ceiling`() {
        assertEquals(
            TokenSavingMode.TOOLS_ONLY,
            ModelCapabilityResolver.effectiveTokenSavingMode(
                ModelCapability.Simple,
                TokenSavingMode.STANDARD
            )
        )
        assertEquals(
            TokenSavingMode.TOOLS_ONLY,
            ModelCapabilityResolver.effectiveTokenSavingMode(
                ModelCapability.Simple,
                TokenSavingMode.TOKEN_SAVER
            )
        )
        assertEquals(
            TokenSavingMode.TOOLS_ONLY,
            ModelCapabilityResolver.effectiveTokenSavingMode(
                ModelCapability.Simple,
                TokenSavingMode.TOOLS_ONLY
            )
        )
    }

    @Test
    fun `Full effective mode SHOULD honor user TokenSavingMode`() {
        assertEquals(
            TokenSavingMode.STANDARD,
            ModelCapabilityResolver.effectiveTokenSavingMode(
                ModelCapability.Full,
                TokenSavingMode.STANDARD
            )
        )
        assertEquals(
            TokenSavingMode.TOKEN_SAVER,
            ModelCapabilityResolver.effectiveTokenSavingMode(
                ModelCapability.Full,
                TokenSavingMode.TOKEN_SAVER
            )
        )
    }

    @Test
    fun `Simple path SHOULD request aggressive #330 schema compression`() {
        val mode = ModelCapabilityResolver.effectiveTokenSavingMode(
            ModelCapability.Simple,
            TokenSavingMode.STANDARD
        )
        assertEquals(TokenSavingMode.TOOLS_ONLY, mode)
        assertEquals(SchemaCompressionLevel.STRIPPED, mode.toSchemaCompression())
    }

    @Test
    fun `isSchemaSimpleEnough SHOULD reject many params enums and nested objects`() {
        val simple = ToolSpec(
            name = "list_hosts",
            description = "List hosts",
            parametersSchema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "search" to JsonObject(
                                mapOf("type" to JsonPrimitive("string"))
                            ),
                            "page" to JsonObject(
                                mapOf("type" to JsonPrimitive("integer"))
                            ),
                        )
                    )
                )
            )
        )
        assertTrue(ModelCapabilityResolver.isSchemaSimpleEnough(simple))

        val tooManyParams = ToolSpec(
            name = "fat_tool",
            description = "Too many params",
            parametersSchema = JsonObject(
                mapOf(
                    "properties" to JsonObject(
                        (1..5).associate { i ->
                            "p$i" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                        }
                    )
                )
            )
        )
        assertFalse(ModelCapabilityResolver.isSchemaSimpleEnough(tooManyParams))

        val fatEnum = ToolSpec(
            name = "enum_tool",
            description = "Wide enum",
            parametersSchema = JsonObject(
                mapOf(
                    "properties" to JsonObject(
                        mapOf(
                            "status" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "enum" to JsonArray(
                                        listOf("a", "b", "c", "d", "e").map { JsonPrimitive(it) }
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        assertFalse(ModelCapabilityResolver.isSchemaSimpleEnough(fatEnum))

        val nested = ToolSpec(
            name = "nested_tool",
            description = "Nested object",
            parametersSchema = JsonObject(
                mapOf(
                    "properties" to JsonObject(
                        mapOf(
                            "filter" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("object"),
                                    "properties" to JsonObject(
                                        mapOf(
                                            "name" to JsonObject(
                                                mapOf("type" to JsonPrimitive("string"))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        assertFalse(ModelCapabilityResolver.isSchemaSimpleEnough(nested))
    }

    @Test
    fun `preferAllowlistAndCap SHOULD enforce hard cap of 10`() {
        val tools = (1..15).map { i ->
            object : io.github.leogallego.ansiblejane.assistant.tools.LocalTool {
                override val spec = ToolSpec("tool_$i", "t$i", JsonObject(emptyMap()))
                override val isDestructive = false
                override suspend fun execute(args: JsonObject) =
                    io.github.leogallego.ansiblejane.assistant.tools.ToolResult(success = true)
            }
        }
        // Sprinkle allowlisted names
        val mixed = listOf(
            object : io.github.leogallego.ansiblejane.assistant.tools.LocalTool {
                override val spec = ToolSpec("list_hosts", "hosts", JsonObject(emptyMap()))
                override val isDestructive = false
                override suspend fun execute(args: JsonObject) =
                    io.github.leogallego.ansiblejane.assistant.tools.ToolResult(success = true)
            }
        ) + tools
        val capped = ModelCapabilityResolver.preferAllowlistAndCap(mixed)
        assertEquals(ModelCapabilityResolver.SIMPLE_HARD_CAP, capped.size)
        assertEquals("list_hosts", capped.first().spec.name)
    }
}
