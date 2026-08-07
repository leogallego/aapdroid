package io.github.leogallego.ansiblejane.network.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class McpConnectionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

fun toolSchemaToJsonObject(schema: ToolSchema): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(schema.type))
    schema.properties?.let { put("properties", it) }
    schema.required?.let { required ->
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}
