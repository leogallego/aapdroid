package io.github.leogallego.ansiblejane.model

data class PopularMcpServer(
    val name: String,
    val url: String,
    val description: String
)

val popularMcpServers = listOf(
    PopularMcpServer(
        name = "Context7",
        url = "https://mcp.context7.com/mcp",
        description = "Up-to-date library & framework documentation"
    ),
    PopularMcpServer(
        name = "Fetch",
        url = "https://mcp-fetch.onrender.com/mcp",
        description = "Fetch web content and convert to markdown"
    ),
    PopularMcpServer(
        name = "DeepWiki",
        url = "https://mcp.deepwiki.com/mcp",
        description = "AI-generated docs for GitHub repositories"
    ),
    PopularMcpServer(
        name = "Sequential Thinking",
        url = "https://mcp-sequential-thinking.onrender.com/mcp",
        description = "Step-by-step problem solving and analysis"
    ),
)
