package io.github.leogallego.ansiblejane.assistant.llm

import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository

/**
 * Creates the platform LiteRT-backed [LlmProvider] for [LlmProviderConfig.OnDevice] (#264).
 * Actuals live in `androidMain`/`jvmMain` — LiteRT types never appear in `commonMain`.
 */
expect object LocalLlmProviderFactory {
    fun create(
        config: LlmProviderConfig.OnDevice,
        modelRepository: ILocalModelRepository,
    ): LlmProvider
}
