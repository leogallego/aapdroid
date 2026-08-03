# Tool Pipeline Architecture

How user messages flow through the tool system — from query to LLM response.

## Components

| Component | Module | Location | Responsibility |
|-----------|--------|----------|---------------|
| **AssistantViewModel** | composeApp | `assistant/presentation/AssistantViewModel.kt` | Orchestrates the pipeline: creates router, budgets tools, starts engine |
| **ToolRouter** | shared | `assistant/engine/ToolRouter.kt` | Selects relevant tools per query via category matching + scoring |
| **ChatEngine** | shared | `assistant/engine/ChatEngine.kt` | Agentic loop: sends tools + message to LLM, executes tool calls, repeats |
| **ToolExecutor** | shared | `assistant/engine/ToolExecutor.kt` | Executes individual tool calls with timeout, caching, and result truncation |
| **LocalTool** | shared | `assistant/tools/local/*.kt` | 61 tools calling AAP APIs directly via Ktor |
| **McpTool** | shared | `assistant/tools/McpTool.kt` | Wraps MCP server tools as the `Tool` interface |
| **McpServerManager** | shared | `network/mcp/McpServerManager.kt` | Multi-server connection lifecycle, tool discovery, exposes `mcpTools` StateFlow |
| **IMcpServerManager** | shared | `network/mcp/IMcpServerManager.kt` | Interface for McpServerManager (added PR #307) |
| **SettingsViewModel** | composeApp | `presentation/settings/SettingsViewModel.kt` | Settings UI state, tool enable/disable, MCP server management |

## Pipeline Flow

```
User sends message
    │
    ▼
┌─────────────────────────────────────────────────┐
│ AssistantViewModel.sendMessage()                │
│                                                 │
│  1. ToolRouter initialized at ViewModel init    │
│     (synchronized, idempotent — PR #307)        │
│  2. Local tools (61) registered at init         │
│     (auto-disables overlapping MCP tools via     │
│      OVERLAP_MAPPING during registration)       │
│  3. MCP tools observed via                      │
│     mcpServerManager.mcpTools StateFlow (#307)  │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│ ToolRouter.getToolsForQuery(message)            │
│                                                 │
│  1. Parse: lowercase → split → remove stopwords │
│     → stem                                      │
│  2. Match categories (8): stemmed query words    │
│     vs category keyword sets                    │
│  3. Filter local tools: name in matched         │
│     category + enabled                          │
│  4. Filter MCP tools: toolset-to-category map   │
│     or resource prefix match                    │
│     + enabled + read-only enforcement           │
│  5. cherryPick(): score by name-part overlap    │
│     with query, boost list/ping/get, penalize   │
│     destructive                                 │
│  6. Return QueryResult(tools, categoryMatched)  │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│ AssistantViewModel (continued)                  │
│                                                 │
│  1. Early return: if no category matched AND    │
│     token mode is TOOLS_ONLY, show guidance     │
│  2. Cap MCP tools by TokenSavingMode:           │
│     STANDARD=10, TOKEN_SAVER=5, TOOLS_ONLY=3    │
│  3. Fallback: if both matchedLocal and          │
│     matchedMcp empty, add ListToolsLocalTool    │
│  4. Set contextChars by mode:                   │
│     STANDARD=16K, TOKEN_SAVER=8K, TOOLS_ONLY=4K │
│  5. Create ToolExecutor(allRegisteredTools)      │
│  6. Create ChatEngine(llmProvider, toolExecutor) │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│ ChatEngine.processMessage(message, history,     │
│                           toolSpecs)            │
│                                                 │
│  1. Convert ToolSpec → Koog ToolDescriptor      │
│     (via ToolDescriptorMapping)                 │
│  2. Build system prompt with tool guidance      │
│  3. Send to LLM provider (streaming)            │
│                                                 │
│  ┌──── Agentic loop (max 10 iterations) ──────┐ │
│  │                                             │ │
│  │  LLM response contains tool call?           │ │
│  │  ├─ YES → ToolExecutor.execute(call)        │ │
│  │  │         ├─ Find tool by name             │ │
│  │  │         ├─ Check 2-min result cache      │ │
│  │  │         ├─ Execute with 30s timeout      │ │
│  │  │         ├─ capResultArray (max 10 items) │ │
│  │  │         ├─ smartTruncate (8K char limit) │ │
│  │  │         └─ Return result to LLM          │ │
│  │  │         → loop back                      │ │
│  │  │                                          │ │
│  │  ├─ NO (text response) → emit to UI, done   │ │
│  │  │                                          │ │
│  │  └─ Repeat detected → break                 │ │
│  └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

## Tool Sources

### Local Tools (61)

Call AAP APIs directly via Ktor. Zero latency to MCP server. Defined in `shared/.../assistant/tools/local/`.

Examples: `list_job_templates`, `launch_job`, `get_host_facts`, `ping`, `list_eda_activations`

Each implements `Tool` interface:
```kotlin
interface Tool {
    val spec: ToolSpec  // name, description, parametersSchema
    suspend fun execute(args: JsonObject): ToolResult
}
```

### MCP Tools

Discovered dynamically from connected MCP servers. Wrapped as `McpTool` implementing the same `Tool` interface. Exposed via `McpServerManager.mcpTools` StateFlow (PR #307).

Discovery flow:
```
McpServerManager.connectAll()
    → MCP SDK client.connect(url)    // SSE + JSON-RPC initialize handshake
    → client.listTools()             // tools/list request
    → McpTool(definition)            // wrap each as Tool interface
    → _mcpTools.value updated        // StateFlow emission
    → ToolRouter observes changes
```

## ToolRouter Details

### Categories (8)

| Category | Keywords (sample) | Local Tools (sample) |
|----------|------------------|---------------------|
| INVENTORY | host, inventory, group, variable | list_inventories, list_hosts, get_host_facts |
| JOBS | job, template, launch, run, playbook | list_job_templates, launch_job, get_job |
| MONITORING | status, health, instance, node, mesh | list_instances, get_mesh_topology, ping |
| USERS | user, team, role, permission, access | (MCP only) |
| SECURITY | credential, vault, secret, token | list_credentials, get_credential |
| CONFIGURATION | project, execution, environment, setting | list_execution_environments, list_projects |
| EDA | eda, rulebook, activation, event, webhook | list_eda_activations, list_eda_audit_rules |
| PLATFORM | platform, gateway, service, sso | list_platform_services, list_platform_users |

### Toolset-to-Category Mapping

MCP tools with a known `toolset` field use category matching instead of prefix matching:
```
job_management → JOBS
inventory_management → INVENTORY
system_monitoring → MONITORING
user_management → USERS
security_compliance → SECURITY
platform_configuration → CONFIGURATION
event_management → EDA
```

### Overlap Prevention

When a local tool exists, its MCP equivalent is auto-disabled via `OVERLAP_MAPPING`.

### Read-Only Enforcement

MCP servers with `readOnly: true` in config have write tools filtered out. Detection by tool name suffix: `_create`, `_update`, `_delete`, `_launch`, `_relaunch`, `_cancel`.

### Per-Tool Enable/Disable

Users can toggle individual tools via Settings → Local Tools and MCP Servers tabs. State persisted via `IAssistantRepository.saveToolState()`.

## Token Optimization

Three tiers control schema verbosity and tool count:

| TokenSavingMode | Description | MCP Tool Cap | Context Chars |
|-----------------|------------|-------------|--------------|
| STANDARD | Full conversation with LLM, tools when relevant | 10 | 16,000 |
| TOKEN_SAVER | Short replies for general chat, tools when relevant | 5 | 8,000 |
| TOOLS_ONLY | Tools only — no general conversation | 3 | 4,000 |

Schema compaction handled by `ToolDescriptorMapping.compactSchema()`.

## Known Issues

- **Stemmer too simplistic**: only handles plurals (-s/-es/-ies), misses -ing/-ed/-er

## Planned Improvements

See issue #120 for the 5-phase improvement plan covering semantic search (Model2Vec), model capability tiers, AAP role filtering, and token optimization. See issue #30 for local LLM backend integration.
