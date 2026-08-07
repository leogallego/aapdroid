package io.github.leogallego.ansiblejane.assistant.engine

import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.TokenSavingMode
import io.github.leogallego.ansiblejane.assistant.tools.Tool
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How much tool-schema / tool-count complexity the active model can handle (#453).
 *
 * ## Mapping guidelines
 *
 * | Source | Typical tier | Notes |
 * |--------|--------------|-------|
 * | Frontier cloud (OpenAI, Gemini, OpenRouter, Groq, Claude-class via OpenRouter) | [Full] | Honor the user's [TokenSavingMode]; post-#330 category routing + compression. |
 * | Self-hosted / Ollama | Heuristic → often [Simple] | Parse size hints from the model id (`7b`, `70b`, …). Unknown → [Simple] (conservative). |
 * | On-device / LiteRT (#264) | [Simple] | Pass `onDevice = true`. Stub until LiteRT lands; no backend here. |
 * | Custom / Abbenay (unknown host) | Heuristic on model id; default [Simple] | Prefer Simple when size/capability is unclear. |
 *
 * ## TokenSavingMode precedence
 *
 * - **Full:** user's [TokenSavingMode] is honored as-is.
 * - **Simple:** the tier sets a **ceiling** on schema richness / context. Effective mode is at
 *   least [TokenSavingMode.TOOLS_ONLY] (most aggressive today). The user may only go *more*
 *   aggressive, never less — so STANDARD/TOKEN_SAVER are raised to TOOLS_ONLY for Simple.
 *
 * Schema compression (#330): TOOLS_ONLY and TOKEN_SAVER both map to
 * [io.github.leogallego.ansiblejane.assistant.tools.SchemaCompressionLevel.STRIPPED].
 */
enum class ModelCapability {
    /** Frontier / large models — full category routing + user TokenSavingMode. */
    Full,

    /**
     * Small / local / on-device models — hard-capped local tools, no MCP,
     * complexity filter, aggressive #330 schema stripping.
     */
    Simple,
}

/**
 * Resolves [ModelCapability] and effective [TokenSavingMode] for ToolRouter / ChatEngine.
 */
object ModelCapabilityResolver {

    /**
     * Hard cap for tools sent per query on [ModelCapability.Simple] (#264 E4B strategy).
     * Soft top-K from #330 still applies underneath this ceiling.
     */
    const val SIMPLE_HARD_CAP = 10

    /**
     * Max JSON-schema properties a Simple-tier tool may expose (Kai-style structural filter).
     * Prefer small list/get tools; reject fat multi-param schemas.
     */
    const val SIMPLE_MAX_PARAMS = 4

    /**
     * Max enum entries allowed on any single parameter for Simple tier.
     * Large enums blow up tokens and confuse small function-call parsers.
     */
    const val SIMPLE_MAX_ENUM_VALUES = 4

    /**
     * Curated “safe simple” local tools preferred when trimming to [SIMPLE_HARD_CAP].
     * Not exclusive — other local tools that pass [isSchemaSimpleEnough] may still be sent
     * if slots remain (FN-safer than Kai’s exclusive allowlist).
     */
    val SIMPLE_SAFE_ALLOWLIST: Set<String> = setOf(
        "ping",
        "search_available_tools",
        "list_hosts",
        "list_inventories",
        "list_groups",
        "list_jobs",
        "list_job_templates",
        "get_job",
        "list_workflow_templates",
        "list_schedules",
        "list_projects",
        "list_credentials",
        "list_credential_types",
        "list_organizations",
        "list_users",
        "list_teams",
        "list_instances",
        "list_instance_groups",
        "get_config",
        "list_execution_environments",
        "list_eda_activations",
        "list_pending_approvals",
    )

    /**
     * @param provider Known/frontier vs self-hosted classification from the active URL.
     * @param model Model id (e.g. `llama3.1:8b`, `gpt-4.1`).
     * @param onDevice Future LiteRT / on-device flag (#264). When true → always [ModelCapability.Simple].
     */
    fun resolve(
        provider: KnownProvider,
        model: String,
        onDevice: Boolean = false,
    ): ModelCapability {
        if (onDevice) return ModelCapability.Simple

        return when (provider) {
            KnownProvider.OPENAI,
            KnownProvider.GOOGLE_GEMINI,
            KnownProvider.OPENROUTER,
            KnownProvider.GROQ -> ModelCapability.Full

            KnownProvider.OLLAMA,
            KnownProvider.ABBENAY,
            KnownProvider.CUSTOM -> resolveFromModelHints(model)
        }
    }

    /**
     * Simple tier forces aggressive saving; Full honors the user setting.
     *
     * Precedence: for Simple, tier ceiling = [TokenSavingMode.TOOLS_ONLY]. User mode cannot
     * relax that (STANDARD / TOKEN_SAVER are raised). There is nothing more aggressive than
     * TOOLS_ONLY today, so Simple always resolves to TOOLS_ONLY.
     */
    fun effectiveTokenSavingMode(
        capability: ModelCapability,
        userMode: TokenSavingMode,
    ): TokenSavingMode = when (capability) {
        ModelCapability.Full -> userMode
        ModelCapability.Simple -> TokenSavingMode.TOOLS_ONLY
    }

    /**
     * Structural “simple enough” check for tool JSON schemas (Kai-inspired).
     * Empty / missing properties count as simple.
     */
    fun isSchemaSimpleEnough(spec: ToolSpec): Boolean {
        val schema = spec.parametersSchema
        val properties = schema["properties"]?.jsonObject ?: return true
        if (properties.size > SIMPLE_MAX_PARAMS) return false

        for ((_, value) in properties) {
            val prop = value.jsonObject
            val type = prop["type"]?.jsonPrimitive?.content
            if (type == "object") return false
            if (type == "array") {
                val items = prop["items"]?.jsonObject
                val itemType = items?.get("type")?.jsonPrimitive?.content
                if (itemType == "object") return false
            }
            val enumValues = prop["enum"]?.jsonArray
            if (enumValues != null && enumValues.size > SIMPLE_MAX_ENUM_VALUES) return false
            // Nested object under properties without type:object (some MCP schemas)
            if (prop.containsKey("properties")) return false
        }
        return true
    }

    /**
     * Prefer allowlisted tools, then preserve relative order of the rest, hard-capped.
     */
    fun preferAllowlistAndCap(tools: List<Tool>): List<Tool> {
        if (tools.size <= SIMPLE_HARD_CAP) {
            val preferred = tools.filter { it.spec.name in SIMPLE_SAFE_ALLOWLIST }
            val others = tools.filter { it.spec.name !in SIMPLE_SAFE_ALLOWLIST }
            return preferred + others
        }
        val preferred = tools.filter { it.spec.name in SIMPLE_SAFE_ALLOWLIST }
        val others = tools.filter { it.spec.name !in SIMPLE_SAFE_ALLOWLIST }
        return (preferred + others).take(SIMPLE_HARD_CAP)
    }

    /**
     * Size / family heuristics for self-hosted model ids.
     * Large parameter counts → Full; small/unknown → Simple (conservative).
     */
    internal fun resolveFromModelHints(model: String): ModelCapability {
        val normalized = model.trim().lowercase()
        if (normalized.isEmpty()) return ModelCapability.Simple

        // Explicit small / local family markers (boundary-aware — avoid "2b" matching inside "72b")
        val simpleFamily = listOf("tiny", "mini", "nano", "phi", "litert", "e2b", "e4b")
        if (simpleFamily.any { it in normalized }) return ModelCapability.Simple

        val paramBillions = extractParamBillions(normalized)
        if (paramBillions != null) {
            // ≥32B class can usually handle richer schemas; below that stay Simple.
            return if (paramBillions >= 32) ModelCapability.Full else ModelCapability.Simple
        }

        // Frontier model names served via a custom / Abbenay proxy
        val frontierHints = listOf(
            "gpt-4", "gpt-5", "o1", "o3", "o4",
            "claude", "gemini-2", "gemini-1.5", "gemini-pro",
            "command-r-plus", "deepseek-v3", "qwen2.5-72b", "qwen3-72b",
        )
        if (frontierHints.any { it in normalized }) return ModelCapability.Full

        return ModelCapability.Simple
    }

    /**
     * Parses `70b`, `8b`, `120b-a12b`, `llama3.1:70b-instruct-q4` → billions of params.
     */
    internal fun extractParamBillions(normalizedModel: String): Int? {
        val match = Regex("""(?:^|[^a-z0-9])(\d{1,3})\s*b(?:[^a-z0-9]|$)""")
            .find(normalizedModel)
            ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
