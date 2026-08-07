package io.github.leogallego.ansiblejane.assistant.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OnDeviceConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun onDevice_roundTripsThroughJson() {
        val cfg = LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it")
        val encoded = json.encodeToString(LlmProviderConfig.serializer(), cfg)
        val decoded = json.decodeFromString(LlmProviderConfig.serializer(), encoded)
        assertEquals(cfg, decoded)
        assertIs<LlmProviderConfig.OnDevice>(decoded)
        assertEquals(TokenSavingMode.TOOLS_ONLY, decoded.tokenSavingMode)
        assertTrue(encoded.contains("on_device"), "serialized JSON should use on_device discriminator")
    }

    @Test
    fun openAiCompatible_map_stillDecodes_regression() {
        val configs = mapOf(
            "OPENAI" to LlmProviderConfig.OpenAiCompatible(
                url = "https://api.openai.com/v1",
                model = "gpt-4.1",
                apiKey = null,
            ),
        )
        val encoded = json.encodeToString(
            MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
            configs,
        )
        val decoded = json.decodeFromString(
            MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
            encoded,
        )
        assertEquals(configs, decoded)
        assertIs<LlmProviderConfig.OpenAiCompatible>(decoded.getValue("OPENAI"))
    }

    @Test
    fun localProvider_hasExpectedMetadata() {
        assertEquals("On-device", KnownProvider.LOCAL.displayName)
        assertEquals("", KnownProvider.LOCAL.baseUrl)
        assertEquals(emptyList(), KnownProvider.LOCAL.defaultModels)
        assertEquals(false, KnownProvider.LOCAL.requiresApiKey)
        assertEquals(false, KnownProvider.LOCAL.urlEditable)
    }

    @Test
    fun fromUrl_empty_doesNotMatchLocal() {
        assertEquals(KnownProvider.CUSTOM, KnownProvider.fromUrl(""))
    }
}
