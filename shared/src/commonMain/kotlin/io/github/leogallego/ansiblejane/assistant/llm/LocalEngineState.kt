package io.github.leogallego.ansiblejane.assistant.llm

/**
 * Lightweight LiteRT engine lifecycle phase for Settings/Assistant "Loading model…" UI.
 * `LlmProvider.isAvailable()` alone can't distinguish "not downloaded" from "loading" —
 * this is exposed as a separate `StateFlow` on the concrete [LocalLlmProvider] actuals.
 */
enum class LocalEngineState {
    Uninitialized,
    Loading,
    Ready,
    Error,
}
