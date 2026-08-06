package io.github.leogallego.ansiblejane.assistant.engine

import io.github.leogallego.ansiblejane.TestOnly
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.assistant.engine.DebugLog as Log
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.assistant.tools.Tool
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.model.McpServerConfig
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class ToolRouter(
    initialLocalTools: List<LocalTool> = emptyList(),
    private val repository: IAssistantRepository? = null
) : SynchronizedObject() {

    data class ToolKey(val name: String, val source: ToolSource, val serverLabel: String? = null) {
        fun toPersistedKey(): String = when (source) {
            ToolSource.LOCAL -> "LOCAL:$name"
            ToolSource.MCP -> "MCP:${serverLabel ?: ""}:$name"
        }

        companion object {
            fun fromPersistedKey(key: String): ToolKey? {
                val firstColon = key.indexOf(':')
                if (firstColon <= 0) return null
                val sourceStr = key.substring(0, firstColon)
                val source = try { ToolSource.valueOf(sourceStr) } catch (_: Exception) { return null }
                val rest = key.substring(firstColon + 1)
                return when (source) {
                    ToolSource.LOCAL -> ToolKey(rest, source)
                    ToolSource.MCP -> {
                        val secondColon = rest.indexOf(':')
                        if (secondColon >= 0) {
                            val serverLabel = rest.substring(0, secondColon).takeIf { it.isNotEmpty() }
                            val name = rest.substring(secondColon + 1)
                            ToolKey(name, source, serverLabel)
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    private val localTools = mutableListOf<LocalTool>()
    private val mcpTools = mutableListOf<Tool>()
    private val autoDisabled = mutableSetOf<ToolKey>()
    private val userDisabled = mutableSetOf<ToolKey>()
    private val userEnabled = mutableSetOf<ToolKey>()

    private val initialized = atomic(false)

    init {
        synchronized(this) {
            if (initialLocalTools.isNotEmpty()) {
                localTools.addAll(initialLocalTools)
                autoDisableOverlappingMcpTools()
            }
        }
    }

    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        val repo = repository ?: return
        val disabled = repo.getDisabledTools()
        val overrides = repo.getEnabledOverrides()
        applyPersistedState(disabled, overrides)
    }

    private enum class Category(
        val keywords: Set<String>,
        val localToolNames: Set<String>
    ) {
        INVENTORY(
            keywords = setOf(
                "host", "hosts", "group", "groups", "inventory", "inventories",
                "infrastructure", "facts", "gather", "info", "server", "servers",
                "machine", "machines", "asset", "assets", "source", "sources",
                "label", "labels", "tag", "tags", "summary", "summaries"
            ),
            localToolNames = setOf(
                "list_inventories", "list_hosts", "get_host_facts", "get_host_job_summaries",
                "list_groups", "list_inventory_sources", "list_labels"
            )
        ),
        JOBS(
            keywords = setOf(
                "job", "jobs", "template", "templates", "launch", "run",
                "schedule", "schedules", "workflow", "playbook", "jt", "wfjt",
                "output", "stdout", "running", "failed", "started", "task",
                "tasks", "command", "error", "errors", "failure", "status",
                "playbooks", "workflows", "execution", "executions",
                "survey", "node", "nodes", "prompt", "variable", "variables",
                "approval", "approvals", "approve", "deny", "pending"
            ),
            localToolNames = setOf(
                "list_job_templates", "launch_job", "get_job", "get_job_stdout", "list_jobs",
                "list_workflow_templates", "launch_workflow", "get_workflow_job",
                "list_schedules", "toggle_schedule",
                "list_workflow_nodes", "get_survey_spec",
                "list_pending_approvals", "approve_workflow", "deny_workflow"
            )
        ),
        MONITORING(
            keywords = setOf(
                "health", "status", "monitor", "metrics", "log", "logs",
                "dashboard", "analytics", "instance", "instances", "mesh",
                "topology", "ping", "cluster", "capacity", "node",
                "monitoring", "healthy", "overview", "nodes", "workers",
                "alive", "up", "down", "diagnostics", "group"
            ),
            localToolNames = setOf("list_instances", "get_instance", "list_instance_groups", "ping", "get_mesh_topology")
        ),
        USERS(
            keywords = setOf(
                "user", "users", "team", "teams", "organization", "organizations",
                "org", "role", "roles", "permission", "permissions", "member",
                "members", "people", "admin", "admins", "token", "tokens",
                "application", "applications", "app", "apps", "access", "rbac",
                "definition", "definitions", "oauth"
            ),
            localToolNames = setOf(
                "list_organizations", "list_users", "list_teams",
                "list_roles", "list_role_definitions",
                "list_applications", "list_tokens"
            )
        ),
        SECURITY(
            keywords = setOf(
                "credential", "credentials", "secret", "secrets", "security",
                "compliance", "policy", "certificate", "creds", "vault",
                "password", "passwords", "key", "keys", "cert", "certs",
                "type", "types"
            ),
            localToolNames = setOf("list_credentials", "get_credential", "list_credential_types")
        ),
        CONFIGURATION(
            keywords = setOf(
                "setting", "settings", "configure", "configuration", "notification",
                "notifications", "label", "labels", "project", "projects",
                "execution", "environment", "environments", "config", "ee",
                "ees", "scm", "repo", "repos", "repository", "alert", "alerts",
                "tag", "tags", "license", "subscription", "version"
            ),
            localToolNames = setOf(
                "list_projects", "get_project", "list_execution_environments",
                "list_notification_templates", "get_settings", "get_config"
            )
        ),
        EDA(
            keywords = setOf(
                "eda", "rulebook", "activation", "event", "audit", "de", "des",
                "rule", "rules", "trigger", "triggers", "webhook", "webhooks",
                "stream", "streams", "decision", "driven", "rulebooks",
                "activations", "events", "environment"
            ),
            localToolNames = setOf(
                "list_eda_audit_rules", "list_eda_activations", "get_eda_activation",
                "list_eda_rulebooks", "list_eda_decision_environments",
                "list_eda_projects", "list_eda_credentials", "list_eda_credential_types",
                "list_eda_event_streams", "list_eda_users"
            )
        ),
        PLATFORM(
            keywords = setOf(
                "platform", "gateway", "authenticator", "authenticators",
                "service", "services", "sso", "saml", "ldap",
                "identity", "authentication", "provider", "providers"
            ),
            localToolNames = setOf(
                "list_platform_organizations", "list_platform_users", "list_platform_teams",
                "list_platform_role_definitions", "list_authenticators",
                "list_platform_services", "list_service_clusters"
            )
        ),
        HUB(
            keywords = setOf(
                "hub", "galaxy", "collection", "collections", "namespace", "namespaces",
                "registry", "registries", "certified", "validated", "published",
                "container", "image", "images",
                "approval", "approvals",
                "role", "roles",
                "ee", "execution", "environment"
            ),
            localToolNames = setOf(
                "list_hub_collections", "list_hub_namespaces", "list_hub_approvals",
                "list_hub_ee_repositories", "list_hub_ee_registries",
                "list_hub_users", "list_hub_groups", "list_hub_roles"
            )
        );

        val stemmedKeywords: Set<String> by lazy {
            keywords.map { stem(it) }.toSet()
        }
    }

    companion object {
        private const val TAG = "ToolRouter"

        // MCP tool names use unprefixed operationIds from the OpenAPI spec.
        // Verified against aap-mcp-server (2026-06-18): no controller./eda./gateway. prefix on wire.
        // Action suffix is _retrieve (not _read) per OpenAPI convention.
        val OVERLAP_MAPPING = mapOf(
            // Jobs & Templates
            "list_job_templates" to setOf("job_templates_list"),
            "launch_job" to setOf("job_templates_launch_create"),
            "get_job" to setOf("jobs_retrieve"),
            "get_job_stdout" to setOf("jobs_stdout_retrieve"),
            "list_jobs" to setOf("jobs_list"),
            "list_workflow_templates" to setOf("workflow_job_templates_list"),
            "launch_workflow" to setOf("workflow_job_templates_launch_create"),
            "get_workflow_job" to setOf("workflow_jobs_retrieve"),
            "list_workflow_nodes" to setOf("workflow_jobs_workflow_nodes_list"),
            "get_survey_spec" to setOf("job_templates_survey_spec_retrieve"),
            "list_pending_approvals" to setOf("workflow_approvals_list"),
            "approve_workflow" to setOf("workflow_approvals_approve_create"),
            "deny_workflow" to setOf("workflow_approvals_deny_create"),
            "list_schedules" to setOf("schedules_list"),
            "toggle_schedule" to setOf("schedules_partial_update", "schedules_update"),
            // Inventory
            "list_inventories" to setOf("inventories_list"),
            "list_hosts" to setOf("hosts_list"),
            "get_host_facts" to setOf("hosts_variable_data_retrieve"),
            "get_host_job_summaries" to setOf("jobs_job_host_summaries_list"),
            "list_groups" to setOf("groups_list"),
            "list_inventory_sources" to setOf("inventory_sources_list"),
            "list_labels" to setOf("labels_list"),
            // Monitoring
            "list_instances" to setOf("instances_list"),
            "get_instance" to setOf("instances_retrieve"),
            "list_instance_groups" to setOf("instance_groups_list"),
            "ping" to setOf("ping_retrieve"),
            "get_mesh_topology" to setOf("mesh_visualizer_retrieve"),
            // Credentials & Security
            "list_credentials" to setOf("credentials_list"),
            "get_credential" to setOf("credentials_retrieve"),
            "list_credential_types" to setOf("credential_types_list"),
            // Configuration
            "list_projects" to setOf("projects_list"),
            "get_project" to setOf("projects_retrieve"),
            "list_execution_environments" to setOf("execution_environments_list"),
            "list_notification_templates" to setOf("notification_templates_list"),
            "get_settings" to setOf("settings_list", "settings_getter", "settings_retrieve"),
            "get_config" to setOf("config_retrieve"),
            // Users & RBAC
            "list_organizations" to setOf("organizations_list"),
            "list_users" to setOf("users_list"),
            "list_teams" to setOf("teams_list"),
            "list_roles" to setOf("roles_list"),
            "list_role_definitions" to setOf("role_definitions_list"),
            "list_applications" to setOf("applications_list"),
            "list_tokens" to setOf("tokens_list"),
            // Hub (no MCP server exists yet — names are speculative)
            "list_hub_collections" to setOf("collections_list"),
            "list_hub_namespaces" to setOf("namespaces_list"),
            "list_hub_approvals" to setOf("collection_versions_list"),
            "list_hub_ee_repositories" to setOf("execution_environments_repositories_list"),
            "list_hub_ee_registries" to setOf("execution_environments_registries_list"),
            "list_hub_users" to setOf("hub_users_list"),
            "list_hub_groups" to setOf("hub_groups_list"),
            "list_hub_roles" to setOf("hub_role_definitions_list"),
            // Platform / Gateway
            "list_platform_organizations" to setOf("organizations_list"),
            "list_platform_users" to setOf("users_list"),
            "list_platform_teams" to setOf("teams_list"),
            "list_platform_role_definitions" to setOf("role_definitions_list"),
            "list_authenticators" to setOf("authenticators_list"),
            "list_platform_services" to setOf("services_list"),
            "list_service_clusters" to setOf("service_clusters_list"),
            // EDA (Phase 3 in aap-mcp-server, not yet exposed)
            "list_eda_audit_rules" to setOf("audit_rules_list"),
            "list_eda_activations" to setOf("activations_list"),
            "get_eda_activation" to setOf("activations_retrieve"),
            "list_eda_rulebooks" to setOf("rulebooks_list"),
            "list_eda_decision_environments" to setOf("decision_environments_list"),
            "list_eda_projects" to setOf("projects_list"),
            "list_eda_credentials" to setOf("credentials_list"),
            "list_eda_credential_types" to setOf("credential_types_list"),
            "list_eda_event_streams" to setOf("event_streams_list"),
            "list_eda_users" to setOf("users_list"),
        )

        private val WRITE_ACTIONS = Tool.WRITE_SUFFIXES

        private val STOP_WORDS = setOf(
            "list", "get", "show", "what", "are", "the", "is", "a", "an",
            "my", "all", "me", "how", "many", "which", "do", "i", "have",
            "can", "tell", "about", "find", "check", "give",
            "of", "for", "in", "on", "to", "and", "or", "with", "from",
            "any", "been"
        )

        private val GREETING_WORDS = setOf(
            "hi", "hello", "hey", "thanks", "thank", "bye", "goodbye", "ok", "okay", "sure"
        )

        private val PRONOUN_WORDS = setOf("you", "we", "they", "he", "she", "it", "your", "our")

        private val TOOL_DISCOVERY_WORDS = setOf(
            "tools", "tool", "capabilities", "capable", "help", "actions", "functions"
        )

        /** Synonym expansion applied to query tokens before stemming (#120 Tier 1). */
        val SYNONYMS = mapOf(
            "playbook" to setOf("job", "template"),
            "playbooks" to setOf("job", "template"),
            "deploy" to setOf("launch", "run", "execute"),
            "deployment" to setOf("launch", "job", "run"),
            "provision" to setOf("launch", "run"),
            "machine" to setOf("host", "server", "node", "instance"),
            "machines" to setOf("host", "server", "node", "instance"),
            "workstation" to setOf("host", "server", "machine"),
            "workstations" to setOf("host", "server", "machine"),
            "cred" to setOf("credential", "secret", "key"),
            "creds" to setOf("credential", "secret", "key"),
            "execute" to setOf("launch", "run", "job"),
            "execution" to setOf("job", "run"),
            "executions" to setOf("job", "run"),
            "worker" to setOf("instance", "node"),
            "workers" to setOf("instance", "node"),
            "secret" to setOf("credential"),
            "secrets" to setOf("credential"),
            "vault" to setOf("credential", "secret"),
            "automation" to setOf("job", "workflow"),
            "ansible" to setOf("job", "playbook", "template"),
        )

        private val TOOLSET_CATEGORY_MAP = mapOf(
            "job_management" to setOf(Category.JOBS),
            "inventory_management" to setOf(Category.INVENTORY),
            "system_monitoring" to setOf(Category.MONITORING),
            "user_management" to setOf(Category.USERS),
            "security_compliance" to setOf(Category.SECURITY),
            "platform_configuration" to setOf(Category.CONFIGURATION),
            "event_management" to setOf(Category.EDA),
            "integration" to setOf(Category.CONFIGURATION, Category.SECURITY, Category.USERS),
            "developer_integration" to setOf(Category.JOBS, Category.MONITORING),
            "hub_management" to setOf(Category.HUB),
        )

        fun getCategoryForTool(toolName: String): String? {
            return Category.entries.firstOrNull { toolName in it.localToolNames }?.name
        }

        fun stem(word: String): String {
            var w = word

            // Longer morphological suffixes first (Tier 1 #120)
            w = when {
                w.endsWith("ies") && w.length > 4 ->
                    w.dropLast(3) + "y"
                w.endsWith("tion") && w.length > 5 ->
                    w.dropLast(3) // execution → execut
                w.endsWith("sion") && w.length > 5 ->
                    w.dropLast(3)
                w.endsWith("ing") && w.length > 5 ->
                    undouble(w.dropLast(3))
                w.endsWith("ed") && w.length > 4 ->
                    undouble(w.dropLast(2))
                w.endsWith("er") && w.length > 5 ->
                    undouble(w.dropLast(2))
                else -> {
                    // Legacy plural chain: -ies/-es/-s then trailing -e
                    val beforeIes = w
                    w = w.removeSuffix("ies").let { if (it != beforeIes) "${it}y" else it }
                    w = w.removeSuffix("es")
                    w = w.removeSuffix("s")
                    w = w.removeSuffix("e")
                    w
                }
            }

            // Plural strip may leave -tion (executions → execution → execut)
            if (w.endsWith("tion") && w.length > 5) w = w.dropLast(3)
            else if (w.endsWith("sion") && w.length > 5) w = w.dropLast(3)

            // Trailing -e after morphological strip (execute → execut)
            if (w.endsWith("e") && w.length > 2) w = w.dropLast(1)

            return if (w.length < 2) word else w
        }

        /** Collapse a doubled final consonant (running → runn → run). */
        private fun undouble(base: String): String {
            if (base.length < 2) return base
            val last = base.last()
            val prev = base[base.lastIndex - 1]
            return if (last == prev && last !in "aeiou") base.dropLast(1) else base
        }

        fun expandSynonyms(words: Set<String>): Set<String> {
            val expanded = words.toMutableSet()
            for (word in words) {
                SYNONYMS[word]?.let { expanded.addAll(it) }
            }
            return expanded
        }

        fun tokenizeQuery(query: String): Set<String> =
            query.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()

        fun stemQueryTokens(queryWords: Set<String>): Set<String> {
            val expanded = expandSynonyms(queryWords - STOP_WORDS)
            return expanded.map { stem(it) }.filter { it.isNotEmpty() }.toSet()
        }

        private fun isTrivialQuery(queryWords: Set<String>): Boolean {
            val meaningful = queryWords - STOP_WORDS - PRONOUN_WORDS - GREETING_WORDS
            return meaningful.isEmpty()
        }

        private fun isToolDiscoveryQuery(queryWords: Set<String>): Boolean {
            if (TOOL_DISCOVERY_WORDS.any { it in queryWords }) return true
            // "what can you do?" — discovery intent without explicit "tools"
            return "what" in queryWords && "do" in queryWords
        }
    }

    fun registerLocalTools(tools: List<LocalTool>) = synchronized(this) {
        localTools.clear()
        localTools.addAll(tools)
        autoDisableOverlappingMcpTools()
    }

    fun registerMcpTools(tools: List<Tool>) = synchronized(this) {
        mcpTools.clear()
        mcpTools.addAll(tools)
        autoDisableOverlappingMcpTools()
    }

    @TestOnly
    fun setToolEnabled(toolName: String, source: ToolSource, serverLabel: String? = null, enabled: Boolean) = synchronized(this) {
        val key = ToolKey(toolName, source, serverLabel)
        if (enabled) {
            userDisabled.remove(key)
            userEnabled.add(key)
        } else {
            userDisabled.add(key)
            userEnabled.remove(key)
        }
    }

    fun isToolEnabled(toolName: String, source: ToolSource, serverLabel: String? = null): Boolean = synchronized(this) {
        val key = ToolKey(toolName, source, serverLabel)
        val isAuto = isAutoDisabledByName(toolName, source)
        key !in userDisabled && (!isAuto || key in userEnabled)
    }

    fun isAutoDisabled(toolName: String, source: ToolSource, serverLabel: String? = null): Boolean = synchronized(this) {
        isAutoDisabledByName(toolName, source)
    }

    private fun isAutoDisabledByName(toolName: String, source: ToolSource): Boolean {
        return autoDisabled.any { it.name == toolName && it.source == source }
    }

    suspend fun toggleToolEnabled(toolName: String, source: ToolSource, serverLabel: String? = null, enabled: Boolean) {
        val snapshot = synchronized(this) {
            val key = ToolKey(toolName, source, serverLabel)
            val isAuto = isAutoDisabledByName(toolName, source)
            if (isAuto) {
                userDisabled.remove(key)
                if (enabled) userEnabled.add(key) else userEnabled.remove(key)
            } else {
                if (enabled) userDisabled.remove(key) else userDisabled.add(key)
                userEnabled.remove(key)
            }
            Pair(
                userDisabled.map { it.toPersistedKey() }.toSet(),
                userEnabled.map { it.toPersistedKey() }.toSet()
            )
        }
        repository?.saveToolState(snapshot.first, snapshot.second)
    }

    fun getPersistedDisabled(): Set<String> = synchronized(this) {
        userDisabled.map { it.toPersistedKey() }.toSet()
    }

    fun getPersistedOverrides(): Set<String> = synchronized(this) {
        userEnabled.map { it.toPersistedKey() }.toSet()
    }

    fun applyPersistedState(disabled: Set<String>, enabledOverrides: Set<String>) = synchronized(this) {
        for (entry in disabled) {
            val key = ToolKey.fromPersistedKey(entry) ?: continue
            userDisabled.add(key)
        }
        for (entry in enabledOverrides) {
            val key = ToolKey.fromPersistedKey(entry) ?: continue
            userEnabled.add(key)
        }
    }

    data class QueryResult(
        val tools: List<Tool>,
        val categoryMatched: Boolean
    )

    fun getToolsForQuery(
        query: String,
        serverConfigs: List<McpServerConfig> = emptyList(),
        aapRole: AapRole? = null
    ): QueryResult = synchronized(this) {
        val queryWords = tokenizeQuery(query)
        val stemmedQuery = stemQueryTokens(queryWords)
        Log.d(TAG, "QUERY: words=$queryWords, stemmed=$stemmedQuery, role=$aapRole")

        val matchedCategories = Category.entries.filter { category ->
            category.stemmedKeywords.any { it in stemmedQuery }
        }

        if (matchedCategories.isEmpty()) {
            return@synchronized noCategoryMatchFallback(queryWords, stemmedQuery, serverConfigs, aapRole)
        }
        Log.d(TAG, "QUERY: matched categories=${matchedCategories.map { it.name }}")

        val matchedLocalNames = matchedCategories.flatMap { it.localToolNames }.toSet()

        val readOnlyLabels = serverConfigs
            .filter { it.readOnly }
            .map { it.label }
            .toSet()

        val filteredLocal = localTools.filter { tool ->
            tool.spec.name in matchedLocalNames &&
                isToolEnabled(tool.spec.name, ToolSource.LOCAL) &&
                passesRoleFilter(tool, aapRole)
        }

        val routedMcp = mutableListOf<Tool>()
        val unroutedMcp = mutableListOf<Tool>()

        for (tool in mcpTools) {
            if (!isToolEnabled(tool.spec.name, ToolSource.MCP, tool.serverLabel)) continue
            if (!passesRoleFilter(tool, aapRole)) continue

            val passesReadOnly = tool.serverLabel !in readOnlyLabels ||
                WRITE_ACTIONS.none { action -> tool.spec.name.endsWith(action) }
            if (!passesReadOnly) continue

            val toolToolset = tool.toolset
            val toolsetCategories = toolToolset?.let { TOOLSET_CATEGORY_MAP[it] }
            when {
                toolsetCategories != null && matchedCategories.any { it in toolsetCategories } ->
                    routedMcp.add(tool)
                toolsetCategories != null -> { }
                toolToolset != null -> {
                    Log.d(TAG, "FILTER: unknown toolset '${toolToolset}' for ${tool.spec.name}, treating as unrouted")
                    unroutedMcp.add(tool)
                }
                else ->
                    unroutedMcp.add(tool)
            }
        }

        Log.d(TAG, "FILTER: ${filteredLocal.size} local, ${routedMcp.size} routed mcp, ${unroutedMcp.size} unrouted mcp")
        val cherryPickedLocal = cherryPick(filteredLocal, stemmedQuery)
        val cherryPickedRouted = cherryPick(routedMcp, stemmedQuery)
        val cherryPickedUnrouted = cherryPick(unroutedMcp, stemmedQuery, requireOverlap = true)
        val allCherryPicked = cherryPickedLocal + cherryPickedRouted + cherryPickedUnrouted
        Log.d(TAG, "CHERRY: ${cherryPickedLocal.size} local, ${cherryPickedRouted.size} routed, " +
            "${cherryPickedUnrouted.size} unrouted [${allCherryPicked.map { it.spec.name }}]")

        QueryResult(allCherryPicked, categoryMatched = true)
    }

    /**
     * Search all enabled tools by name + description tokens (meta-search / #120).
     * Stable: score desc, then name asc.
     */
    fun searchAvailableTools(
        query: String,
        maxResults: Int = 20,
        serverConfigs: List<McpServerConfig> = emptyList(),
        aapRole: AapRole? = null
    ): List<Tool> = synchronized(this) {
        val stemmedQuery = stemQueryTokens(tokenizeQuery(query))
        if (stemmedQuery.isEmpty()) return emptyList()
        val candidates = collectEnabledTools(serverConfigs, aapRole)
        return cherryPick(candidates, stemmedQuery, requireOverlap = true).take(maxResults.coerceAtLeast(0))
    }

    private fun noCategoryMatchFallback(
        queryWords: Set<String>,
        stemmedQuery: Set<String>,
        serverConfigs: List<McpServerConfig>,
        aapRole: AapRole?
    ): QueryResult {
        Log.d(TAG, "QUERY: no categories matched — meta-search fallback")

        if (isTrivialQuery(queryWords) && !isToolDiscoveryQuery(queryWords)) {
            Log.d(TAG, "QUERY: trivial/greeting — empty tools")
            return QueryResult(emptyList(), categoryMatched = false)
        }

        if (stemmedQuery.isNotEmpty()) {
            val searched = cherryPick(
                collectEnabledTools(serverConfigs, aapRole),
                stemmedQuery,
                requireOverlap = true
            )
            if (searched.isNotEmpty()) {
                Log.d(TAG, "META: description/name search hit ${searched.size} tools")
                return QueryResult(searched, categoryMatched = false)
            }
        }

        if (isToolDiscoveryQuery(queryWords) || stemmedQuery.isNotEmpty()) {
            val meta = localTools.firstOrNull { tool ->
                tool.spec.name == "search_available_tools" &&
                    isToolEnabled(tool.spec.name, ToolSource.LOCAL)
            } ?: localTools.firstOrNull { tool ->
                tool.spec.name == "list_tools" &&
                    isToolEnabled(tool.spec.name, ToolSource.LOCAL)
            }
            if (meta != null) {
                Log.d(TAG, "META: injecting ${meta.spec.name}")
                return QueryResult(listOf(meta), categoryMatched = false)
            }
        }

        return QueryResult(emptyList(), categoryMatched = false)
    }

    private fun collectEnabledTools(
        serverConfigs: List<McpServerConfig>,
        aapRole: AapRole?
    ): List<Tool> {
        val readOnlyLabels = serverConfigs
            .filter { it.readOnly }
            .map { it.label }
            .toSet()
        val enabledLocal = localTools.filter {
            isToolEnabled(it.spec.name, ToolSource.LOCAL) && passesRoleFilter(it, aapRole)
        }
        val enabledMcp = mcpTools.filter { tool ->
            isToolEnabled(tool.spec.name, ToolSource.MCP, tool.serverLabel) &&
                passesRoleFilter(tool, aapRole) &&
                (tool.serverLabel !in readOnlyLabels ||
                    WRITE_ACTIONS.none { action -> tool.spec.name.endsWith(action) })
        }
        return enabledLocal + enabledMcp
    }

    private fun passesRoleFilter(tool: Tool, aapRole: AapRole?): Boolean {
        if (aapRole != AapRole.AUDITOR) return true
        if (tool.isDestructive) return false
        return WRITE_ACTIONS.none { action -> tool.spec.name.endsWith(action) }
    }

    private data class ScoredTool(val tool: Tool, val score: Int, val overlap: Int)

    private fun cherryPick(
        tools: List<Tool>,
        stemmedQuery: Set<String>,
        requireOverlap: Boolean = false
    ): List<Tool> {
        val scored = tools.map { tool ->
            val nameParts = tool.spec.name
                .split(".", "_")
                .map { stem(it) }
                .filter { it.isNotEmpty() }
                .toSet()
            val descParts = tool.spec.description.lowercase()
                .split(Regex("\\W+"))
                .filter { it.isNotEmpty() && it !in STOP_WORDS }
                .map { stem(it) }
                .filter { it.isNotEmpty() }
                .toSet()
            val nameOverlap = (nameParts intersect stemmedQuery).size
            val descOverlap = (descParts intersect stemmedQuery).size
            val overlap = nameOverlap + descOverlap
            var score = nameOverlap * 10 + descOverlap * 3
            if (tool.spec.name.contains("list") || tool.spec.name.contains("ping")) score += 3
            if (tool.spec.name.contains("get") || tool.spec.name.contains("read") || tool.spec.name.contains("retrieve")) score += 1
            if (nameOverlap > 0 && tool.isDestructive) score -= 5
            ScoredTool(tool, score, overlap)
        }
        Log.d(TAG, "SCORES: ${scored.map { "${it.tool.spec.name}=${it.score}" }}")
        return scored
            .filter { it.score > 0 && (!requireOverlap || it.overlap > 0) }
            // #439: secondary sort by name for stable KV-cache-friendly ordering
            .sortedWith(compareByDescending<ScoredTool> { it.score }.thenBy { it.tool.spec.name })
            .map { it.tool }
    }

    fun getAllRegisteredTools(): List<Pair<Tool, ToolSource>> = synchronized(this) {
        val result = mutableListOf<Pair<Tool, ToolSource>>()
        localTools.forEach { result.add(it to ToolSource.LOCAL) }
        mcpTools.forEach { result.add(it to ToolSource.MCP) }
        result
    }

    private fun autoDisableOverlappingMcpTools() {
        autoDisabled.clear()
        val activeLocalNames = localTools.map { it.spec.name }.toSet()
        var disabledCount = 0
        for (localName in activeLocalNames) {
            val overlappingMcpNames = OVERLAP_MAPPING[localName] ?: continue
            for (mcpName in overlappingMcpNames) {
                autoDisabled.add(ToolKey(mcpName, ToolSource.MCP))
                disabledCount++
            }
        }
        if (disabledCount > 0) {
            Log.d(TAG, "OVERLAP: disabled $disabledCount MCP tools overlapping with ${activeLocalNames.size} local tools")
        }
    }

}
