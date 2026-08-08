package io.github.leogallego.ansiblejane.assistant.data

enum class KnownProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModels: List<String>,
    val requiresApiKey: Boolean,
    val urlEditable: Boolean
) {
    OPENROUTER(
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModels = listOf(
            "google/gemini-2.5-flash",
            "google/gemini-2.5-pro",
            "anthropic/claude-sonnet-4",
            "openai/gpt-4.1",
            "openai/gpt-4.1-mini",
            "meta-llama/llama-4-maverick",
            "nvidia/nemotron-3-super-120b-a12b:free"
        ),
        requiresApiKey = true,
        urlEditable = false
    ),
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        defaultModels = listOf(
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "o4-mini",
            "gpt-4o"
        ),
        requiresApiKey = true,
        urlEditable = false
    ),
    GOOGLE_GEMINI(
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash"
        ),
        requiresApiKey = true,
        urlEditable = false
    ),
    OLLAMA(
        displayName = "Ollama",
        baseUrl = "http://localhost:11434/v1",
        defaultModels = listOf(
            "llama3.1:8b",
            "qwen2.5:7b",
            "qwen3:8b",
            "mistral:7b",
            "gemma2:9b",
            "phi3:mini"
        ),
        requiresApiKey = false,
        urlEditable = true
    ),
    GROQ(
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "gemma2-9b-it",
            "mixtral-8x7b-32768"
        ),
        requiresApiKey = true,
        urlEditable = false
    ),
    ABBENAY(
        displayName = "Abbenay",
        baseUrl = "http://localhost:8787/v1",
        defaultModels = emptyList(),
        requiresApiKey = false,
        urlEditable = true
    ),
    LOCAL(
        displayName = "On-device",
        baseUrl = "",
        defaultModels = emptyList(),
        requiresApiKey = false,
        urlEditable = false,
    ),
    CUSTOM(
        displayName = "Custom",
        baseUrl = "",
        defaultModels = emptyList(),
        requiresApiKey = true,
        urlEditable = true
    );

    companion object {
        fun fromUrl(url: String): KnownProvider {
            val normalized = url.trimEnd('/')
            return entries.firstOrNull {
                it != CUSTOM && it.baseUrl.isNotEmpty() &&
                    normalized.equals(it.baseUrl.trimEnd('/'), ignoreCase = true)
            } ?: CUSTOM
        }
    }
}
