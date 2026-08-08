package io.github.leogallego.ansiblejane.assistant.presentation

import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapability
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapabilityResolver
import io.github.leogallego.ansiblejane.assistant.local.LOCAL_MODEL_CATALOG

/**
 * Resolves [ModelCapability] from the active LLM config (#264 / #453).
 * On-device always maps to Simple via `onDevice = true`.
 */
fun resolveCapabilityForConfig(config: LlmProviderConfig): ModelCapability = when (config) {
    is LlmProviderConfig.OnDevice ->
        ModelCapabilityResolver.resolve(KnownProvider.LOCAL, config.modelId, onDevice = true)
    is LlmProviderConfig.OpenAiCompatible ->
        ModelCapabilityResolver.resolve(
            KnownProvider.fromUrl(config.url),
            config.model,
            onDevice = false,
        )
}

/** On-device context budget from catalog [defaultContextTokens]; unknown modelId → 4096. */
fun resolveContextCharsForConfig(config: LlmProviderConfig.OnDevice): Int =
    LOCAL_MODEL_CATALOG.find { it.id == config.modelId }?.defaultContextTokens ?: 4_096
