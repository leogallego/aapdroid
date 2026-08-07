package io.github.leogallego.ansiblejane.assistant.local

/**
 * On-device Gemma 4 catalog for LiteRT-LM (#264).
 *
 * Download URLs are pinned to immutable Hugging Face commit SHAs (not `resolve/main`).
 * File [LocalModel.sha256] values are the Git LFS OID (SHA-256) from the HF tree API.
 *
 * Sources (2026-08-07):
 * - E4B: https://huggingface.co/api/models/litert-community/gemma-4-E4B-it-litert-lm
 *   commit `2eee7ac325f20eb8c9ac1d0e972f7c84663062da`
 * - 12B: https://huggingface.co/api/models/litert-community/gemma-4-12B-it-litert-lm
 *   commit `b33be37e07c25ee94e6d99dd0a484b32158f7b49`
 */
val LOCAL_MODEL_CATALOG: List<LocalModel> = listOf(
    LocalModel(
        id = "gemma-4-e4b-it",
        displayName = "Gemma 4 E4B IT",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
                "resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        gpuMemoryMb = 710,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 75_000,
        onDeviceTier = OnDeviceTier.E4B,
    ),
    LocalModel(
        id = "gemma-4-12b-it",
        displayName = "Gemma 4 12B IT",
        fileName = "gemma-4-12B-it.litertlm",
        sizeBytes = 6_547_589_312L,
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/" +
                "resolve/b33be37e07c25ee94e6d99dd0a484b32158f7b49/gemma-4-12B-it.litertlm",
        sha256 = "74fc29a10c20eb5b3ced6c389471a7994a0ffd657255b2a1c764262fb9054aef",
        gpuMemoryMb = 4_000,
        defaultContextTokens = 8_192,
        maxContextTokens = 32_768,
        // Estimated from Kai E4B (75_000); HF does not publish KV-per-token for 12B.
        kvPerTokenBytes = 150_000,
        onDeviceTier = OnDeviceTier.LARGE,
        isRecommended = true,
    ),
)
