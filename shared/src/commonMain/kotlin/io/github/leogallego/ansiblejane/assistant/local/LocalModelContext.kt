package io.github.leogallego.ansiblejane.assistant.local

/** Discrete step for on-device context size sliders (Kai-style, 1K tokens). */
const val CONTEXT_TOKEN_STEP = 1_024

/**
 * Clamps [requested] into `[defaultTokens, maxTokens]` on [CONTEXT_TOKEN_STEP] boundaries.
 * Values below [defaultTokens] snap up to the default (floor is catalog default, not a lower safe floor).
 */
fun clampContextTokens(defaultTokens: Int, maxTokens: Int, requested: Int): Int {
    val lo = defaultTokens.coerceAtMost(maxTokens)
    val hi = maxTokens.coerceAtLeast(lo)
    if (requested <= lo) return lo
    val stepped = lo + (((requested - lo) / CONTEXT_TOKEN_STEP) * CONTEXT_TOKEN_STEP)
    return stepped.coerceIn(lo, hi)
}

/**
 * Resolves the effective on-device context window for a catalog model.
 *
 * @param contextTokens user selection; `<= 0` means “use catalog default”
 */
fun resolveOnDeviceContextTokens(modelId: String, contextTokens: Int): Int {
    val model = LOCAL_MODEL_CATALOG.find { it.id == modelId }
    val defaultTokens = model?.defaultContextTokens ?: 4_096
    val maxTokens = model?.maxContextTokens ?: defaultTokens
    val requested = if (contextTokens > 0) contextTokens else defaultTokens
    return clampContextTokens(defaultTokens, maxTokens, requested)
}
