package io.github.leogallego.ansiblejane.assistant.presentation

import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapability
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapabilityResolver

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
