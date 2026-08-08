package io.github.leogallego.ansiblejane.assistant.tools

import io.github.leogallego.ansiblejane.model.McpToolDefinition
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject

class CachedMcpTool(
    private val mcpToolDef: McpToolDefinition,
    override val serverLabel: String,
    override val toolset: String? = null,
    val readOnly: Boolean = false,
    private val toolInvoker: McpToolInvoker
) : Tool {

    override val isDestructive: Boolean =
        Tool.WRITE_SUFFIXES.any { mcpToolDef.name.endsWith(it) }

    override val spec: ToolSpec = ToolSpec(
        name = mcpToolDef.name,
        description = "[$serverLabel] ${mcpToolDef.description}".take(Tool.MAX_DESCRIPTION_CHARS),
        parametersSchema = mcpToolDef.inputSchema
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            toolInvoker.invokeMcpTool(serverLabel, mcpToolDef.name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(
                success = false,
                data = "Connection error: ${e.message}",
                errorType = ErrorType.CONNECTION_ERROR
            )
        }
    }
}
