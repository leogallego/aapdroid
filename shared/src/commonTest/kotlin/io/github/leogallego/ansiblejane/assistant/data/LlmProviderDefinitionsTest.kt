package io.github.leogallego.ansiblejane.assistant.data

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmProviderDefinitionsTest {

    @Test
    fun `SHOULD return OPENROUTER WHEN url matches OpenRouter GIVEN exact url`() {
        assertEquals(KnownProvider.OPENROUTER, KnownProvider.fromUrl("https://openrouter.ai/api/v1"))
    }

    @Test
    fun `SHOULD return OPENROUTER WHEN url has trailing slash GIVEN OpenRouter url`() {
        assertEquals(KnownProvider.OPENROUTER, KnownProvider.fromUrl("https://openrouter.ai/api/v1/"))
    }

    @Test
    fun `SHOULD return OPENAI WHEN url matches OpenAI GIVEN exact url`() {
        assertEquals(KnownProvider.OPENAI, KnownProvider.fromUrl("https://api.openai.com/v1"))
    }

    @Test
    fun `SHOULD return GOOGLE_GEMINI WHEN url matches Gemini GIVEN exact url`() {
        assertEquals(
            KnownProvider.GOOGLE_GEMINI,
            KnownProvider.fromUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        )
    }

    @Test
    fun `SHOULD return OLLAMA WHEN url matches Ollama GIVEN localhost url`() {
        assertEquals(KnownProvider.OLLAMA, KnownProvider.fromUrl("http://localhost:11434/v1"))
    }

    @Test
    fun `SHOULD return GROQ WHEN url matches Groq GIVEN exact url`() {
        assertEquals(KnownProvider.GROQ, KnownProvider.fromUrl("https://api.groq.com/openai/v1"))
    }

    @Test
    fun `SHOULD return ABBENAY WHEN url matches Abbenay GIVEN localhost url`() {
        assertEquals(KnownProvider.ABBENAY, KnownProvider.fromUrl("http://localhost:8787/v1"))
    }

    @Test
    fun `SHOULD return CUSTOM WHEN url is unknown GIVEN arbitrary url`() {
        assertEquals(KnownProvider.CUSTOM, KnownProvider.fromUrl("https://my-server.com/v1"))
    }

    @Test
    fun `SHOULD return CUSTOM WHEN url is empty GIVEN blank string`() {
        assertEquals(KnownProvider.CUSTOM, KnownProvider.fromUrl(""))
    }

    @Test
    fun `SHOULD match case-insensitively WHEN url has different casing GIVEN OpenAI url`() {
        assertEquals(KnownProvider.OPENAI, KnownProvider.fromUrl("https://API.OPENAI.COM/v1"))
    }

    @Test
    fun `SHOULD have non-empty defaultModels WHEN provider is known GIVEN all known providers`() {
        KnownProvider.entries
            .filter {
                it != KnownProvider.CUSTOM &&
                    it != KnownProvider.ABBENAY &&
                    it != KnownProvider.LOCAL
            }
            .forEach { provider ->
                assert(provider.defaultModels.isNotEmpty()) {
                    "${provider.displayName} should have default models"
                }
            }
    }
}
