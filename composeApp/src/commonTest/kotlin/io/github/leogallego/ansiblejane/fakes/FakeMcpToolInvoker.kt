package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.assistant.tools.McpToolInvoker
import io.github.leogallego.ansiblejane.assistant.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class FakeMcpToolInvoker : McpToolInvoker {
    override suspend fun invokeMcpTool(
        serverLabel: String,
        toolName: String,
        args: JsonObject
    ): ToolResult = ToolResult(success = true, data = "")
}
