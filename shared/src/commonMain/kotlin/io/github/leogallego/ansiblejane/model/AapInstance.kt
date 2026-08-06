package io.github.leogallego.ansiblejane.model

import kotlinx.serialization.Serializable

@Serializable
data class McpServerConfig(
    val url: String,
    val label: String,
    val enabled: Boolean = true,
    val isAutoDetected: Boolean = false,
    val useInstanceAuth: Boolean = true,
    val readOnly: Boolean = false,
    val toolset: String? = null,
    val headers: Map<String, String> = emptyMap()
)

data class AapInstance(
    val id: String,
    val baseUrl: String,
    val token: String,
    val alias: String? = null,
    val apiVersion: String = "v2",
    val trustSelfSigned: Boolean = false,
    val certFingerprint: String? = null,
    val mcpServerUrls: List<McpServerConfig>? = null,
    val mcpEnabled: Boolean = false,
    val instanceInfo: InstanceInfo? = null,
    /** From `/api/v2/me/` — used by ToolRouter auditor filtering (#120). */
    val isSuperuser: Boolean = false,
    val isSystemAuditor: Boolean = false
) {
    val displayLabel: String
        get() = alias ?: extractHost(baseUrl)

    val hostname: String
        get() = extractHost(baseUrl)

    override fun toString(): String =
        "AapInstance(id=$id, baseUrl=***, token=***, alias=$alias, apiVersion=$apiVersion)"
}

private fun extractHost(url: String): String {
    val withoutScheme = url
        .removePrefix("https://")
        .removePrefix("http://")
    return withoutScheme.substringBefore("/").substringBefore(":")
}
