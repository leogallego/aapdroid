package io.github.leogallego.ansiblejane.assistant.tools

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.github.leogallego.ansiblejane.model.McpToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.IOException

class McpTool(
    private val client: Client,
    private val mcpToolDef: McpToolDefinition,
    override val serverLabel: String,
    override val toolset: String? = null
) : Tool {

    companion object {
        const val MAX_PAGE_SIZE = 10
        private val WRITE_SUFFIXES = Tool.WRITE_SUFFIXES
    }

    override val isDestructive: Boolean =
        WRITE_SUFFIXES.any { mcpToolDef.name.endsWith(it) }

    override val spec: ToolSpec = ToolSpec(
        name = mcpToolDef.name,
        description = "[$serverLabel] ${mcpToolDef.description}".take(Tool.MAX_DESCRIPTION_CHARS),
        parametersSchema = mcpToolDef.inputSchema
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            val cappedArgs = capPageSize(args)
            val result = client.callTool(mcpToolDef.name, cappedArgs)

            val text = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }

            if (result.isError == true) {
                ToolResult(
                    success = false,
                    data = text,
                    errorType = ErrorType.SERVER_ERROR
                )
            } else {
                ToolResult(success = true, data = text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            ToolResult(success = false, data = "Tool call timed out", errorType = ErrorType.TIMEOUT)
        } catch (e: IOException) {
            ToolResult(
                success = false,
                data = "Connection error: ${e.message}",
                errorType = ErrorType.CONNECTION_ERROR
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                data = "Unexpected error: ${e.message}",
                errorType = ErrorType.SERVER_ERROR
            )
        }
    }

    private fun capPageSize(args: JsonObject, max: Int = MAX_PAGE_SIZE): JsonObject {
        val current = args["page_size"]?.jsonPrimitive?.intOrNull
        if (current != null && current <= max) return args
        return buildJsonObject {
            args.forEach { (k, v) -> put(k, v) }
            put("page_size", JsonPrimitive(current?.coerceAtMost(max) ?: max))
        }
    }

}
