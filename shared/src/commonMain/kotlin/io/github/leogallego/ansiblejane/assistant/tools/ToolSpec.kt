package io.github.leogallego.ansiblejane.assistant.tools

import kotlinx.serialization.json.JsonObject

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject
)

sealed interface Tool {
    val spec: ToolSpec
    val isDestructive: Boolean
        get() = false
    val serverLabel: String?
        get() = null
    val toolset: String?
        get() = null
    suspend fun execute(args: JsonObject): ToolResult

    companion object {
        const val MAX_DESCRIPTION_CHARS = 300

        /**
         * Read-only MCP allowlist (#335). When a server is `readOnly`, only tools
         * whose names end with one of these suffixes are exposed. Unknown verbs
         * are blocked by default (safer than maintaining an exhaustive write list).
         */
        val READ_SUFFIXES = setOf(
            "_list", "_retrieve", "_read", "_getter"
        )

        /**
         * Known write/destructive suffixes. Used for [isDestructive] scoring and
         * auditor role filtering — not for MCP `readOnly` hard gates (#335).
         */
        val WRITE_SUFFIXES = setOf(
            "_create", "_update", "_delete",
            "_launch", "_relaunch", "_cancel",
            "_partial_update", "_approve", "_deny",
            "_copy", "_sync"
        )
    }
}

data class ToolResult(
    val success: Boolean,
    val data: String? = null,
    val errorType: ErrorType? = null
)

enum class ErrorType {
    CONNECTION_ERROR,
    AUTH_ERROR,
    NOT_FOUND,
    TIMEOUT,
    SERVER_ERROR
}
