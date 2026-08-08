package io.github.leogallego.ansiblejane.assistant.presentation

import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapability
import kotlin.test.Test
import kotlin.test.assertEquals

class AssistantCapabilityTest {

    @Test
    fun `OnDevice config resolves to Simple with onDevice true`() {
        val capability = resolveCapabilityForConfig(
            LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it"),
        )
        assertEquals(ModelCapability.Simple, capability)
    }

    @Test
    fun `frontier OpenAiCompatible config resolves to Full`() {
        val capability = resolveCapabilityForConfig(
            LlmProviderConfig.OpenAiCompatible(
                url = "https://api.openai.com/v1",
                model = "gpt-4.1",
                apiKey = "sk-test",
            ),
        )
        assertEquals(ModelCapability.Full, capability)
    }

    @Test
    fun `OnDevice E4B resolves contextChars to 4096`() {
        val chars = resolveContextCharsForConfig(LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it"))
        assertEquals(4_096, chars)
    }

    @Test
    fun `OnDevice 12B resolves contextChars to 8192`() {
        val chars = resolveContextCharsForConfig(LlmProviderConfig.OnDevice(modelId = "gemma-4-12b-it"))
        assertEquals(8_192, chars)
    }

    @Test
    fun `OnDevice unknown modelId resolves contextChars to 4096 fallback`() {
        val chars = resolveContextCharsForConfig(LlmProviderConfig.OnDevice(modelId = "unknown-model"))
        assertEquals(4_096, chars)
    }

    @Test
    fun `small Ollama OpenAiCompatible config resolves to Simple`() {
        val capability = resolveCapabilityForConfig(
            LlmProviderConfig.OpenAiCompatible(
                url = "http://localhost:11434/v1",
                model = "llama3.1:8b",
            ),
        )
        assertEquals(ModelCapability.Simple, capability)
    }
}
