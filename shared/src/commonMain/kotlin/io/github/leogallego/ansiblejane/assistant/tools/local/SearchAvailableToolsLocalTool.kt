package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import kotlinx.serialization.Serializable

/**
 * Meta-search tool injected when category routing finds no match (#120 Tier 1).
 * Searches registered tool names + descriptions and returns matching summaries.
 */
class SearchAvailableToolsLocalTool(
    private val routerProvider: () -> ToolRouter
) : AapLocalTool<SearchAvailableToolsLocalTool.Args>(
    typeToken<Args>(),
    Args.serializer(),
    name = "search_available_tools",
    description = "Search available tools by name or description when the current tools " +
        "do not cover the user's request. Pass a short natural-language query."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Natural-language search query for tools")
        val query: String,
        @property:LLMDescription("Maximum number of tools to return (default 10)")
        val limit: Int = 10,
    )

    override suspend fun execute(args: Args): String {
        val hits = routerProvider().searchAvailableTools(args.query, maxResults = args.limit.coerceIn(1, 50))
        if (hits.isEmpty()) {
            return "No tools matched \"${args.query}\". Try different keywords " +
                "(e.g. hosts, jobs, credentials, eda)."
        }
        val sb = StringBuilder()
        sb.appendLine("Matching tools (${hits.size}):")
        hits.forEach { tool ->
            sb.appendLine("- ${tool.spec.name}: ${tool.spec.description}")
        }
        return sb.toString()
    }
}
