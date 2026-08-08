package io.github.leogallego.ansiblejane.assistant.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TokenSavingMode {
    @SerialName("standard")
    STANDARD,
    @SerialName("token_saver")
    TOKEN_SAVER,
    @SerialName("minimal")
    TOOLS_ONLY;

    val displayName: String
        get() = when (this) {
            STANDARD -> "Standard"
            TOKEN_SAVER -> "Token Saver"
            TOOLS_ONLY -> "Tools Only"
        }

    val description: String
        get() = when (this) {
            STANDARD -> "Full conversation with LLM, tools when relevant"
            TOKEN_SAVER -> "Short replies for general chat, tools when relevant"
            TOOLS_ONLY -> "Tools only — no general conversation"
        }
}

@Serializable
sealed interface LlmProviderConfig {
    val tokenSavingMode: TokenSavingMode

    @Serializable
    @SerialName("openai_compatible")
    data class OpenAiCompatible(
        val url: String,
        val model: String,
        val apiKey: String? = null,
        override val tokenSavingMode: TokenSavingMode = TokenSavingMode.STANDARD
    ) : LlmProviderConfig

    @Serializable
    @SerialName("on_device")
    data class OnDevice(
        val modelId: String,
        override val tokenSavingMode: TokenSavingMode = TokenSavingMode.TOOLS_ONLY,
        /**
         * User-selected LiteRT context window in tokens.
         * `0` means “use catalog [defaultContextTokens]” (backward-compatible default).
         */
        val contextTokens: Int = 0,
    ) : LlmProviderConfig
}
