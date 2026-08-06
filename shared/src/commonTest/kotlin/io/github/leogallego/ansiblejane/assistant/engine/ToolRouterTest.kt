package io.github.leogallego.ansiblejane.assistant.engine

import io.github.leogallego.ansiblejane.TestOnly
import io.github.leogallego.ansiblejane.assistant.tools.ToolStub
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.assistant.tools.ToolResult
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.model.User
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(TestOnly::class)
class ToolRouterTest {

    companion object {
        val ALL_LOCAL_TOOL_NAMES = listOf(
            "list_inventories", "list_hosts", "get_host_facts", "get_host_job_summaries",
            "list_groups", "list_inventory_sources", "list_labels",
            "list_job_templates", "launch_job", "get_job", "get_job_stdout", "list_jobs",
            "list_workflow_templates", "launch_workflow", "get_workflow_job",
            "list_schedules", "toggle_schedule", "list_workflow_nodes", "get_survey_spec",
            "list_pending_approvals", "approve_workflow", "deny_workflow",
            "list_instances", "get_instance", "list_instance_groups", "ping", "get_mesh_topology",
            "list_credentials", "get_credential", "list_credential_types",
            "list_projects", "get_project", "list_execution_environments",
            "list_notification_templates", "get_settings", "get_config",
            "list_organizations", "list_users", "list_teams",
            "list_roles", "list_role_definitions", "list_applications", "list_tokens",
            "list_platform_organizations", "list_platform_users", "list_platform_teams",
            "list_platform_role_definitions", "list_authenticators",
            "list_platform_services", "list_service_clusters",
            "list_eda_audit_rules", "list_eda_activations", "get_eda_activation",
            "list_eda_rulebooks", "list_eda_decision_environments",
            "list_eda_projects", "list_eda_credentials", "list_eda_credential_types",
            "list_eda_event_streams", "list_eda_users",
            "list_hub_collections", "list_hub_namespaces", "list_hub_approvals",
            "list_hub_ee_repositories", "list_hub_ee_registries",
            "list_hub_users", "list_hub_groups", "list_hub_roles"
        )
    }

    private lateinit var router: ToolRouter

    private fun mcpTool(name: String, serverLabel: String = "aap", toolset: String? = null) =
        ToolStub(name, serverLabel = serverLabel, toolset = toolset,
            description = "[$serverLabel] description of $name")

    private fun localTool(
        name: String,
        destructive: Boolean = false,
        description: String = "Local: $name"
    ) = object : LocalTool {
        override val spec = ToolSpec(name, description, JsonObject(emptyMap()))
        override val isDestructive = destructive
        override suspend fun execute(args: JsonObject) = ToolResult(success = true)
    }

    @BeforeTest
    fun setup() {
        router = ToolRouter()
    }

    // --- Category matching tests ---

    @Test
    fun `SHOULD select inventory tools WHEN query mentions hosts`() {
        val inventoryLocal = localTool("list_hosts")
        val inventoryLocal2 = localTool("list_inventories")
        val jobLocal = localTool("list_jobs")

        router.registerLocalTools(listOf(inventoryLocal, inventoryLocal2, jobLocal))
        val result = router.getToolsForQuery("list my hosts").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hosts" in names)
        assertTrue("list_inventories" in names)
        assertFalse("list_jobs" in names)
    }

    @Test
    fun `SHOULD select job tools WHEN query mentions launch and template`() {
        val tools = listOf(
            localTool("list_job_templates"),
            localTool("launch_job", destructive = true),
            localTool("get_job"),
            localTool("list_hosts")
        )

        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("launch a job template").tools
        val names = result.map { it.spec.name }

        assertTrue("list_job_templates" in names)
        assertTrue("launch_job" in names)
        assertTrue("get_job" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD return no tools WHEN no category matches`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_jobs"),
            localTool("list_job_templates")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("hello how are you").tools
        assertTrue(result.isEmpty())
    }

    @Test
    fun `SHOULD return no tools WHEN query is a greeting`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_jobs"),
            localTool("list_job_templates")
        )
        router.registerLocalTools(tools)

        assertTrue(router.getToolsForQuery("hi").tools.isEmpty())
        assertTrue(router.getToolsForQuery("hello there").tools.isEmpty())
        assertTrue(router.getToolsForQuery("thanks").tools.isEmpty())
    }

    @Test
    fun `SHOULD include tools from multiple matched categories WHEN query spans categories`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_jobs"),
            localTool("get_job"),
            localTool("list_eda_audit_rules")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show hosts running failed jobs").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hosts" in names)
        assertTrue("list_jobs" in names)
        assertTrue("get_job" in names)
    }

    @Test
    fun `SHOULD select EDA tools WHEN query mentions eda or rulebook`() {
        val tools = listOf(
            localTool("list_eda_audit_rules"),
            localTool("list_hosts"),
            localTool("list_jobs")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show eda audit rules").tools
        val names = result.map { it.spec.name }

        assertTrue("list_eda_audit_rules" in names)
        assertFalse("list_hosts" in names)
        assertFalse("list_jobs" in names)
    }

    // --- Local tool priority tests ---

    @Test
    fun `SHOULD auto-disable overlapping MCP tools WHEN local tools are registered`() {
        val localTools = listOf(
            localTool("list_job_templates"),
            localTool("list_hosts")
        )
        router.registerLocalTools(localTools)

        assertFalse(router.isToolEnabled("job_templates_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("hosts_list", ToolSource.MCP))
        assertTrue(router.isToolEnabled("users_list", ToolSource.MCP))
    }

    @Test
    fun `SHOULD allow re-enabling MCP tools via setToolEnabled`() {
        router.registerLocalTools(listOf(localTool("list_job_templates")))
        assertFalse(router.isToolEnabled("job_templates_list", ToolSource.MCP))

        router.setToolEnabled("job_templates_list", ToolSource.MCP, "aap", true)
        assertTrue(router.isToolEnabled("job_templates_list", ToolSource.MCP, "aap"))
    }

    @Test
    fun `SHOULD allow disabling local tools via setToolEnabled`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))
        assertTrue(router.isToolEnabled("list_hosts", ToolSource.LOCAL))

        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)
        assertFalse(router.isToolEnabled("list_hosts", ToolSource.LOCAL))
    }

    @Test
    fun `SHOULD not include disabled local tools in query results`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories")
        )
        router.registerLocalTools(tools)
        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)

        val result = router.getToolsForQuery("show my hosts and inventories").tools
        val names = result.map { it.spec.name }

        assertFalse("list_hosts" in names)
        assertTrue("list_inventories" in names)
    }

    @Test
    fun `SHOULD return empty WHEN no tools registered at all`() {
        val result = router.getToolsForQuery("list hosts").tools
        assertTrue(result.isEmpty())
    }

    @Test
    fun `SHOULD list all registered tools via getAllRegisteredTools`() {
        router.registerLocalTools(listOf(localTool("list_hosts"), localTool("list_jobs")))
        val all = router.getAllRegisteredTools()

        assertEquals(2, all.size)
        assertTrue(all.all { it.second == ToolSource.LOCAL })
    }

    @Test
    fun `SHOULD preserve destructive flag on local tools`() {
        val destructive = localTool("launch_job", destructive = true)
        val readOnly = localTool("list_jobs", destructive = false)

        router.registerLocalTools(listOf(destructive, readOnly))
        val all = router.getAllRegisteredTools()

        val launchTool = all.first { it.first.spec.name == "launch_job" }.first as LocalTool
        val listTool = all.first { it.first.spec.name == "list_jobs" }.first as LocalTool

        assertTrue(launchTool.isDestructive)
        assertFalse(listTool.isDestructive)
    }

    @Test
    fun `SHOULD select workflow tools WHEN query mentions workflow`() {
        val tools = listOf(
            localTool("list_workflow_templates"),
            localTool("launch_workflow", destructive = true),
            localTool("get_workflow_job"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show my workflow templates").tools
        val names = result.map { it.spec.name }

        assertTrue("list_workflow_templates" in names)
        assertTrue("launch_workflow" in names)
        assertTrue("get_workflow_job" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select schedule tools WHEN query mentions schedules`() {
        val tools = listOf(
            localTool("list_schedules"),
            localTool("toggle_schedule", destructive = true),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show my schedules").tools
        val names = result.map { it.spec.name }

        assertTrue("list_schedules" in names)
        assertTrue("toggle_schedule" in names)
        assertFalse("list_hosts" in names)
    }

    // --- MCP tool tests ---

    private val readOnlyConfig = McpServerConfig(url = "https://aap:8448/mcp", label = "aap", readOnly = true)
    private val readWriteConfig = McpServerConfig(url = "https://aap:8448/mcp", label = "aap", readOnly = false)

    @Test
    fun `SHOULD exclude write MCP tools WHEN readOnly is true`() {
        val tools = listOf(
            mcpTool("hosts_list", toolset = "inventory_management"),
            mcpTool("hosts_create", toolset = "inventory_management"),
            mcpTool("hosts_update", toolset = "inventory_management"),
            mcpTool("hosts_delete", toolset = "inventory_management"),
            mcpTool("inventories_list", toolset = "inventory_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("list my hosts", listOf(readOnlyConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("hosts_list" in names)
        assertTrue("inventories_list" in names)
        assertFalse("hosts_create" in names)
        assertFalse("hosts_update" in names)
        assertFalse("hosts_delete" in names)
    }

    @Test
    fun `SHOULD keep all MCP tools WHEN readOnly is false`() {
        val tools = listOf(
            mcpTool("hosts_list"),
            mcpTool("hosts_create"),
            mcpTool("hosts_delete")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("list hosts", listOf(readWriteConfig)).tools
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `SHOULD exclude launch and cancel MCP actions WHEN readOnly is true`() {
        val tools = listOf(
            mcpTool("jobs_retrieve"),
            mcpTool("job_templates_launch_create"),
            mcpTool("jobs_relaunch_create"),
            mcpTool("jobs_cancel_create")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("show me jobs", listOf(readOnlyConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("jobs_retrieve" in names)
        assertFalse("job_templates_launch_create" in names)
        assertFalse("jobs_relaunch_create" in names)
        assertFalse("jobs_cancel_create" in names)
    }

    @Test
    fun `SHOULD only filter matching server label WHEN multiple MCP servers configured`() {
        val tools = listOf(
            mcpTool("hosts_create", "aap"),
            mcpTool("hosts_list", "aap"),
            mcpTool("hosts_create", "knowledge")
        )
        val configs = listOf(
            McpServerConfig(url = "https://aap:8448/mcp", label = "aap", readOnly = true),
            McpServerConfig(url = "https://kb:3000/mcp", label = "knowledge", readOnly = false)
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("list hosts", configs).tools
        val names = result.map { it.spec.name to it.spec.description }

        assertTrue(names.any { it.first == "hosts_list" && it.second.contains("[aap]") })
        assertFalse(names.any { it.first == "hosts_create" && it.second.contains("[aap]") })
        assertTrue(names.any { it.first == "hosts_create" && it.second.contains("[knowledge]") })
    }

    @Test
    fun `SHOULD select MCP inventory tools WHEN query mentions hosts`() {
        val tools = listOf(
            mcpTool("hosts_list", toolset = "inventory_management"),
            mcpTool("groups_list", toolset = "inventory_management"),
            mcpTool("jobs_retrieve", toolset = "job_management"),
            mcpTool("users_list", toolset = "user_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("list my hosts", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("hosts_list" in names)
        assertTrue("groups_list" in names)
        assertFalse("jobs_retrieve" in names)
        assertFalse("users_list" in names)
    }

    @Test
    fun `SHOULD select MCP job tools WHEN query mentions launch and template`() {
        // Compound actions like _launch_create don't match via prefix extraction
        // (substringBeforeLast("_") gives "job_templates_launch", not "job_templates").
        // In production, toolset-based routing handles this. See #333.
        val tools = listOf(
            mcpTool("hosts_list", toolset = "inventory_management"),
            mcpTool("job_templates_list", toolset = "job_management"),
            mcpTool("job_templates_launch_create", toolset = "job_management"),
            mcpTool("jobs_retrieve", toolset = "job_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("launch a job template", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("job_templates_list" in names)
        assertTrue("job_templates_launch_create" in names)
        assertTrue("jobs_retrieve" in names)
        assertFalse("hosts_list" in names)
    }

    @Test
    fun `SHOULD select MCP monitoring tools WHEN query mentions health`() {
        val tools = listOf(
            mcpTool("hosts_list", toolset = "inventory_management"),
            mcpTool("instances_list", toolset = "system_monitoring"),
            mcpTool("ping_retrieve", toolset = "system_monitoring"),
            mcpTool("mesh_visualizer_retrieve", toolset = "system_monitoring")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("check system health", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("instances_list" in names)
        assertTrue("ping_retrieve" in names)
        assertFalse("hosts_list" in names)
    }

    @Test
    fun `SHOULD select MCP user tools WHEN query mentions users or teams`() {
        val tools = listOf(
            mcpTool("users_list", toolset = "user_management"),
            mcpTool("teams_list", toolset = "user_management"),
            mcpTool("organizations_list", toolset = "user_management"),
            mcpTool("hosts_list", toolset = "inventory_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("list users in my team", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("users_list" in names)
        assertTrue("teams_list" in names)
        assertTrue("organizations_list" in names)
        assertFalse("hosts_list" in names)
    }

    @Test
    fun `SHOULD select MCP security tools WHEN query mentions credentials`() {
        val tools = listOf(
            mcpTool("credentials_list", toolset = "security_compliance"),
            mcpTool("credential_types_list", toolset = "security_compliance"),
            mcpTool("hosts_list", toolset = "inventory_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("show my credentials", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("credentials_list" in names)
        assertTrue("credential_types_list" in names)
        assertFalse("hosts_list" in names)
    }

    @Test
    fun `SHOULD select MCP config tools WHEN query mentions settings or projects`() {
        val tools = listOf(
            mcpTool("settings_retrieve", toolset = "platform_configuration"),
            mcpTool("projects_list", toolset = "platform_configuration"),
            mcpTool("notification_templates_list", toolset = "platform_configuration"),
            mcpTool("hosts_list", toolset = "inventory_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("show project settings", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("settings_retrieve" in names)
        assertTrue("projects_list" in names)
        assertTrue("notification_templates_list" in names)
        assertFalse("hosts_list" in names)
    }

    @Test
    fun `SHOULD select MCP tools by toolset WHEN compound names used`() {
        val tools = listOf(
            mcpTool("workflow_job_templates_list", toolset = "job_management"),
            mcpTool("inventories_list", toolset = "inventory_management"),
            mcpTool("hosts_list", toolset = "inventory_management")
        )
        router.registerMcpTools(tools)
        val result = router.getToolsForQuery("show my workflow templates", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("workflow_job_templates_list" in names)
        assertFalse("hosts_list" in names)
    }

    @Test
    fun `SHOULD return no MCP tools WHEN query matches no category`() {
        val tools = listOf(
            mcpTool("hosts_list"),
            mcpTool("jobs_retrieve"),
            mcpTool("users_list")
        )
        router.registerMcpTools(tools)

        assertTrue(router.getToolsForQuery("hi", listOf(readWriteConfig)).tools.isEmpty())
        assertTrue(router.getToolsForQuery("hello there", listOf(readWriteConfig)).tools.isEmpty())
        assertTrue(router.getToolsForQuery("thanks", listOf(readWriteConfig)).tools.isEmpty())
    }

    // --- Non-AAP MCP tool passthrough (issue #333) ---

    @Test
    fun `SHOULD include non-AAP MCP tool WHEN query words overlap with tool name`() {
        val tools = listOf(
            mcpTool("query_cmdb_assets", "cmdb-server"),
            mcpTool("controller.hosts_list")
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("show me all assets", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("query_cmdb_assets" in names, "Non-AAP tool with query overlap should be included")
    }

    @Test
    fun `SHOULD exclude non-AAP MCP tool WHEN no query word overlap (cherry-pick filters)`() {
        val tools = listOf(
            mcpTool("query_cmdb_assets", "cmdb-server"),
            mcpTool("controller.hosts_list")
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("show me all hosts", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("controller.hosts_list" in names, "AAP tool should be included")
        assertFalse("query_cmdb_assets" in names, "Non-AAP tool with no query overlap should be excluded by cherry-pick")
    }

    @Test
    fun `SHOULD exclude non-AAP MCP tool with list bonus WHEN no query word overlap`() {
        val tools = listOf(
            mcpTool("list_network_devices", "netbox"),
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("show me all hosts", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertFalse("list_network_devices" in names, "Non-AAP tool should be excluded despite list bonus (no word overlap)")
    }

    @Test
    fun `SHOULD include non-AAP MCP tool with list in name WHEN query words overlap`() {
        val tools = listOf(
            mcpTool("list_network_devices", "netbox"),
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("list hosts and network devices", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("list_network_devices" in names, "Non-AAP tool with word overlap should be included")
    }

    @Test
    fun `SHOULD still filter AAP MCP tools by toolset category`() {
        val tools = listOf(
            mcpTool("hosts_list", toolset = "inventory_management"),
            mcpTool("jobs_list", toolset = "job_management")
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("show me hosts", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("hosts_list" in names, "hosts_list should be included for host query")
        assertFalse("jobs_list" in names, "jobs_list should be excluded by toolset category mismatch")
    }

    @Test
    fun `SHOULD exclude known-toolset tool with name overlap WHEN category does not match query`() {
        val tools = listOf(
            mcpTool("hosts_job_host_summaries_list", toolset = "job_management"),
            mcpTool("hosts_list", toolset = "inventory_management")
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("list my hosts", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("hosts_list" in names, "hosts_list should be included for host query")
        assertFalse("hosts_job_host_summaries_list" in names, "hosts_job_host_summaries_list should be excluded despite name overlap")
    }

    // --- Mixed local + MCP tests ---

    @Test
    fun `SHOULD prefer local tools and disable overlapping MCP tools by default`() {
        val local = listOf(localTool("list_job_templates"))
        val mcp = listOf(
            mcpTool("job_templates_list"),
            mcpTool("users_list")
        )
        router.registerLocalTools(local)
        router.registerMcpTools(mcp)

        val result = router.getToolsForQuery("show my job templates and users").tools
        val names = result.map { it.spec.name }

        assertTrue("list_job_templates" in names)
        assertFalse("job_templates_list" in names)
        assertTrue("users_list" in names)
    }

    @Test
    fun `SHOULD return both local and MCP tools WHEN no overlap`() {
        val local = listOf(localTool("list_hosts"))
        val mcp = listOf(mcpTool("users_list"))

        router.registerLocalTools(local)
        router.registerMcpTools(mcp)

        val result = router.getToolsForQuery("show hosts and users").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hosts" in names)
        assertTrue("users_list" in names)
    }

    // --- Phase 2 local tool tests ---

    @Test
    fun `SHOULD select monitoring tools WHEN query mentions instances`() {
        val tools = listOf(
            localTool("list_instances"),
            localTool("get_instance"),
            localTool("list_instance_groups"),
            localTool("ping"),
            localTool("get_mesh_topology"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show cluster instances").tools
        val names = result.map { it.spec.name }

        assertTrue("list_instances" in names)
        assertTrue("get_instance" in names)
        assertTrue("list_instance_groups" in names)
        assertTrue("ping" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select monitoring tools WHEN query mentions health`() {
        val tools = listOf(
            localTool("list_instances"),
            localTool("ping"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("check system health").tools
        val names = result.map { it.spec.name }

        assertTrue("list_instances" in names)
        assertTrue("ping" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select credential tools WHEN query mentions credentials`() {
        val tools = listOf(
            localTool("list_credentials"),
            localTool("get_credential"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show my credentials").tools
        val names = result.map { it.spec.name }

        assertTrue("list_credentials" in names)
        assertTrue("get_credential" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select project tools WHEN query mentions projects`() {
        val tools = listOf(
            localTool("list_projects"),
            localTool("get_project"),
            localTool("list_execution_environments"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("list projects").tools
        val names = result.map { it.spec.name }

        assertTrue("list_projects" in names)
        assertTrue("get_project" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select EDA activation tools WHEN query mentions activations`() {
        val tools = listOf(
            localTool("list_eda_activations"),
            localTool("get_eda_activation"),
            localTool("list_eda_audit_rules"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show eda activations").tools
        val names = result.map { it.spec.name }

        assertTrue("list_eda_activations" in names)
        assertTrue("get_eda_activation" in names)
        assertTrue("list_eda_audit_rules" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD auto-disable Phase 2 MCP overlaps WHEN local tools registered`() {
        val localTools = listOf(
            localTool("list_instances"),
            localTool("ping"),
            localTool("list_credentials"),
            localTool("list_projects"),
            localTool("list_eda_activations")
        )
        router.registerLocalTools(localTools)

        assertFalse(router.isToolEnabled("instances_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("ping_retrieve", ToolSource.MCP))
        assertFalse(router.isToolEnabled("credentials_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("projects_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("activations_list", ToolSource.MCP))
        assertTrue(router.isToolEnabled("users_list", ToolSource.MCP))
    }

    @Test
    fun `SHOULD rank list tools above write tools for budget cuts`() {
        val tools = listOf(
            localTool("launch_job", destructive = true),
            localTool("get_job_stdout"),
            localTool("list_job_templates"),
            localTool("get_job"),
            localTool("list_jobs"),
            localTool("list_workflow_templates"),
            localTool("launch_workflow", destructive = true),
            localTool("get_workflow_job"),
            localTool("list_schedules"),
            localTool("toggle_schedule", destructive = true)
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("what job templates are available").tools

        assertTrue(result.isNotEmpty())
        val top5 = result.take(5).map { it.spec.name }
        assertTrue("list_job_templates" in top5)
        assertTrue("list_jobs" in top5)
    }

    @Test
    fun `SHOULD boost query-matching tools in ranking`() {
        val tools = listOf(
            localTool("list_schedules"),
            localTool("toggle_schedule", destructive = true),
            localTool("list_job_templates"),
            localTool("launch_job", destructive = true),
            localTool("get_job"),
            localTool("list_jobs")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show my schedules").tools
        val names = result.map { it.spec.name }

        assertEquals("list_schedules", names[0])
    }

    @Test
    fun `SHOULD select monitoring tools WHEN query mentions mesh topology`() {
        val tools = listOf(
            localTool("get_mesh_topology"),
            localTool("list_instances"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show mesh topology").tools
        val names = result.map { it.spec.name }

        assertTrue("get_mesh_topology" in names)
        assertTrue("list_instances" in names)
        assertFalse("list_hosts" in names)
    }

    // --- Stemming tests ---

    @Test
    fun `SHOULD match plurals via stemming - orgs matches USERS`() {
        router.registerLocalTools(emptyList())
        router.registerMcpTools(listOf(mcpTool("organizations_list", toolset = "user_management")))

        val result = router.getToolsForQuery("list orgs").tools
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.spec.name == "organizations_list" })
    }

    @Test
    fun `SHOULD match plurals via stemming - creds matches SECURITY`() {
        val tools = listOf(localTool("list_credentials"), localTool("get_credential"))
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show my creds").tools
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `SHOULD match plurals via stemming - inventories matches INVENTORY`() {
        val tools = listOf(localTool("list_inventories"), localTool("list_hosts"))
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show inventories").tools
        val names = result.map { it.spec.name }

        assertTrue("list_inventories" in names)
    }

    // --- Stemmer guard tests ---

    @Test
    fun `stem SHOULD return original WHEN result would be single char or empty`() {
        assertEquals("de", ToolRouter.stem("de"))
        assertEquals("ee", ToolRouter.stem("ee"))
        assertEquals("des", ToolRouter.stem("des"))
        assertEquals("ees", ToolRouter.stem("ees"))
    }

    @Test
    fun `stem SHOULD allow 2-char results`() {
        assertEquals("jt", ToolRouter.stem("jts"))
        assertEquals("up", ToolRouter.stem("up"))
    }

    @Test
    fun `stem SHOULD work normally WHEN result is 3 or more chars`() {
        assertEquals("org", ToolRouter.stem("orgs"))
        assertEquals("host", ToolRouter.stem("hosts"))
        assertEquals("credential", ToolRouter.stem("credentials"))
        assertEquals("inventory", ToolRouter.stem("inventories"))
    }

    @Test
    fun `stem SHOULD strip ing ed er and tion suffixes`() {
        assertEquals("run", ToolRouter.stem("running"))
        assertEquals("monitor", ToolRouter.stem("monitoring"))
        assertEquals("fail", ToolRouter.stem("failed"))
        assertEquals("launch", ToolRouter.stem("launched"))
        assertEquals("launch", ToolRouter.stem("launcher"))
        assertEquals("execut", ToolRouter.stem("execution"))
        assertEquals("execut", ToolRouter.stem("executions"))
        assertEquals("execut", ToolRouter.stem("execute"))
    }

    @Test
    fun `stem SHOULD keep setting intact to avoid set false-match`() {
        assertEquals("setting", ToolRouter.stem("setting"))
        assertEquals("setting", ToolRouter.stem("settings"))
    }

    @Test
    fun `SHOULD NOT match CONFIGURATION WHEN query is please set a reminder`() {
        router.registerLocalTools(
            listOf(
                localTool("get_settings"),
                localTool("list_notification_templates"),
                localTool("list_jobs")
            )
        )
        val result = router.getToolsForQuery("please set a reminder")
        assertFalse(result.categoryMatched)
        assertTrue(result.tools.isEmpty() || result.tools.none { it.spec.name == "get_settings" })
    }

    @Test
    fun `SHOULD match CONFIGURATION WHEN query asks for settings`() {
        router.registerLocalTools(
            listOf(localTool("get_settings"), localTool("list_hosts"))
        )
        val result = router.getToolsForQuery("show settings").tools
        assertTrue(result.any { it.spec.name == "get_settings" })
    }

    @Test
    fun `SHOULD match JOBS WHEN query uses launcher stemmed to launch`() {
        val tools = listOf(
            localTool("launch_job", destructive = true),
            localTool("list_job_templates"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("use the launcher").tools
        val names = result.map { it.spec.name }

        assertTrue("launch_job" in names || "list_job_templates" in names)
        assertFalse("list_hosts" in names)
    }

    // --- New keyword tests ---

    @Test
    fun `SHOULD match JOBS via abbreviation jt`() {
        val tools = listOf(
            localTool("list_job_templates"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show jt").tools
        assertTrue(result.any { it.spec.name == "list_job_templates" })
    }

    @Test
    fun `SHOULD match USERS via abbreviation rbac`() {
        router.registerMcpTools(listOf(
            mcpTool("roles_list"),
            mcpTool("hosts_list")
        ))

        val result = router.getToolsForQuery("check rbac roles").tools
        assertTrue(result.any { it.spec.name == "roles_list" })
    }

    @Test
    fun `SHOULD match MONITORING via keyword up`() {
        val tools = listOf(
            localTool("list_instances"),
            localTool("ping"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("is AAP up?").tools
        assertTrue(result.isNotEmpty())
        assertFalse(result.any { it.spec.name == "list_hosts" })
    }

    @Test
    fun `SHOULD match JOBS via keyword error`() {
        val tools = listOf(
            localTool("list_jobs"),
            localTool("get_job"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("any errors?").tools
        assertTrue(result.isNotEmpty())
        assertFalse(result.any { it.spec.name == "list_hosts" })
    }

    @Test
    fun `SHOULD match EDA via keyword de for decision environment`() {
        val tools = listOf(
            localTool("list_eda_activations"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show de").tools
        assertTrue(result.any { it.spec.name == "list_eda_activations" })
    }

    @Test
    fun `SHOULD match CONFIGURATION via keyword ee for execution environment`() {
        val tools = listOf(
            localTool("list_execution_environments"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("list ee").tools
        assertTrue(result.any { it.spec.name == "list_execution_environments" })
    }

    @Test
    fun `SHOULD match CONFIGURATION via keyword scm`() {
        val tools = listOf(
            localTool("list_projects"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show scm projects").tools
        assertTrue(result.any { it.spec.name == "list_projects" })
    }

    @Test
    fun `SHOULD match INVENTORY via keyword facts`() {
        val tools = listOf(
            localTool("get_host_facts"),
            localTool("list_hosts"),
            localTool("list_jobs")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("gather facts").tools
        val names = result.map { it.spec.name }

        assertTrue("get_host_facts" in names)
        assertFalse("list_jobs" in names)
    }

    // --- Dual-category keyword tests ---

    @Test
    fun `SHOULD match both JOBS and MONITORING via keyword status`() {
        val tools = listOf(
            localTool("list_jobs"),
            localTool("get_job"),
            localTool("list_instances"),
            localTool("ping"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("what is the status").tools
        val names = result.map { it.spec.name }

        assertTrue("list_jobs" in names || "get_job" in names)
        assertTrue("list_instances" in names || "ping" in names)
    }

    @Test
    fun `SHOULD match both INVENTORY and MONITORING via keyword group`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories"),
            localTool("list_instance_groups")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show groups").tools
        val names = result.map { it.spec.name }

        assertTrue(names.size >= 2)
    }

    // --- Cherry-pick tests ---

    @Test
    fun `SHOULD cherry-pick relevant tools from matched category`() {
        val tools = listOf(
            localTool("list_job_templates"),
            localTool("launch_job", destructive = true),
            localTool("get_job"),
            localTool("get_job_stdout"),
            localTool("list_jobs"),
            localTool("list_workflow_templates"),
            localTool("launch_workflow", destructive = true),
            localTool("get_workflow_job"),
            localTool("list_schedules"),
            localTool("toggle_schedule", destructive = true)
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show my schedules").tools
        val names = result.map { it.spec.name }

        assertTrue("list_schedules" in names)
        assertTrue("toggle_schedule" in names)
    }

    @Test
    fun `SHOULD cherry-pick MCP tools by name overlap`() {
        val tools = listOf(
            mcpTool("users_list"),
            mcpTool("users_retrieve"),
            mcpTool("teams_list"),
            mcpTool("organizations_list"),
            mcpTool("tokens_list"),
            mcpTool("roles_list")
        )
        router.registerMcpTools(tools)

        val result = router.getToolsForQuery("list users", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertTrue("users_list" in names)
        assertTrue("users_retrieve" in names)
    }

    @Test
    fun `SHOULD fallback to list tools WHEN no cherry-pick overlap`() {
        val tools = listOf(
            localTool("list_instances"),
            localTool("get_instance"),
            localTool("list_instance_groups"),
            localTool("ping"),
            localTool("get_mesh_topology")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("is everything healthy?").tools
        val names = result.map { it.spec.name }

        assertTrue("list_instances" in names)
        assertTrue("list_instance_groups" in names)
        assertTrue("ping" in names)
    }

    @Test
    fun `SHOULD cherry-pick audit rules as EDA not SECURITY`() {
        val tools = listOf(
            localTool("list_eda_audit_rules"),
            localTool("list_credentials"),
            localTool("get_credential")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("show audit rules").tools
        val names = result.map { it.spec.name }

        assertTrue("list_eda_audit_rules" in names)
    }

    // --- Stop words tests ---

    @Test
    fun `SHOULD ignore expanded stop words in matching`() {
        val tools = listOf(localTool("list_hosts"), localTool("list_jobs"))
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("for any of the been").tools
        assertTrue(result.isEmpty())
    }

    @Test
    fun `categoryMatched SHOULD be false WHEN only stop words in query`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))
        val result = router.getToolsForQuery("show me all of the")
        assertFalse(result.categoryMatched)
    }

    // --- Platform category tests ---

    @Test
    fun `SHOULD select platform tools WHEN query mentions gateway`() {
        val tools = listOf(
            localTool("list_platform_organizations"),
            localTool("list_platform_users"),
            localTool("list_platform_services"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show gateway services").tools
        val names = result.map { it.spec.name }

        assertTrue("list_platform_services" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select platform tools WHEN query mentions authenticators`() {
        val tools = listOf(
            localTool("list_authenticators"),
            localTool("list_platform_organizations"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("list authenticators").tools
        val names = result.map { it.spec.name }

        assertTrue("list_authenticators" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select platform tools WHEN query mentions platform`() {
        val tools = listOf(
            localTool("list_platform_organizations"),
            localTool("list_platform_users"),
            localTool("list_platform_teams"),
            localTool("list_platform_role_definitions"),
            localTool("list_authenticators"),
            localTool("list_platform_services"),
            localTool("list_service_clusters"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show platform services").tools
        val names = result.map { it.spec.name }

        assertTrue("list_platform_services" in names)
        assertTrue("list_service_clusters" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD auto-disable platform MCP overlaps WHEN local tools registered`() {
        val localTools = listOf(
            localTool("list_platform_organizations"),
            localTool("list_authenticators"),
            localTool("list_platform_services")
        )
        router.registerLocalTools(localTools)

        assertFalse(router.isToolEnabled("organizations_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("authenticators_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("services_list", ToolSource.MCP))
        assertTrue(router.isToolEnabled("teams_list", ToolSource.MCP))
    }

    @Test
    fun `SHOULD select platform tools WHEN query mentions SSO`() {
        val tools = listOf(
            localTool("list_authenticators"),
            localTool("list_platform_services"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show sso providers").tools
        val names = result.map { it.spec.name }

        assertTrue("list_authenticators" in names)
        assertFalse("list_hosts" in names)
    }

    // --- Hub routing tests ---

    @Test
    fun `SHOULD select hub tools WHEN query mentions hub or collections`() {
        val tools = listOf(
            localTool("list_hub_collections"),
            localTool("list_hub_namespaces"),
            localTool("list_hub_ee_repositories"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("show hub collections").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hub_collections" in names)
        assertTrue("list_hub_namespaces" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD select hub tools WHEN query mentions galaxy or namespaces`() {
        val tools = listOf(
            localTool("list_hub_namespaces"),
            localTool("list_hub_collections"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)
        val result = router.getToolsForQuery("list galaxy namespaces").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hub_namespaces" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD auto-disable hub MCP overlaps WHEN local tools registered`() {
        val localTools = listOf(
            localTool("list_hub_collections"),
            localTool("list_hub_users"),
            localTool("list_hub_ee_repositories")
        )
        router.registerLocalTools(localTools)

        assertFalse(router.isToolEnabled("collections_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("hub_users_list", ToolSource.MCP))
        assertFalse(router.isToolEnabled("execution_environments_repositories_list", ToolSource.MCP))
        assertTrue(router.isToolEnabled("hub_groups_list", ToolSource.MCP))
    }

    @Test
    fun `getCategoryForTool SHOULD return HUB for list_hub_collections`() {
        assertEquals("HUB", ToolRouter.getCategoryForTool("list_hub_collections"))
    }

    // --- Disabled tools filtering (issue #282) ---

    @Test
    fun `SHOULD not include disabled MCP tools in query results`() {
        val tools = listOf(
            mcpTool("hosts_list", toolset = "inventory_management"),
            mcpTool("groups_list", toolset = "inventory_management"),
            mcpTool("users_list", toolset = "user_management")
        )
        router.registerMcpTools(tools)
        router.setToolEnabled("hosts_list", ToolSource.MCP, "aap", false)

        val result = router.getToolsForQuery("list my hosts", listOf(readWriteConfig)).tools
        val names = result.map { it.spec.name }

        assertFalse("hosts_list" in names)
        assertTrue("groups_list" in names)
        assertFalse("users_list" in names, "users_list should be excluded by category")
    }

    @Test
    fun `SHOULD exclude multiple disabled tools from query results`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories"),
            localTool("list_groups")
        )
        router.registerLocalTools(tools)
        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)
        router.setToolEnabled("list_inventories", ToolSource.LOCAL, enabled = false)

        val result = router.getToolsForQuery("show my hosts and inventories").tools
        val names = result.map { it.spec.name }

        assertFalse("list_hosts" in names)
        assertFalse("list_inventories" in names)
        assertTrue("list_groups" in names)
    }

    @Test
    fun `applyPersistedState SHOULD parse key format and disable tools`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_jobs"),
            localTool("list_inventories")
        )
        router.registerLocalTools(tools)
        router.registerMcpTools(listOf(mcpTool("users_list")))

        router.applyPersistedState(setOf("LOCAL:list_hosts", "MCP:aap:users_list"), emptySet())

        assertFalse(router.isToolEnabled("list_hosts", ToolSource.LOCAL))
        assertTrue(router.isToolEnabled("list_jobs", ToolSource.LOCAL))
        assertFalse(router.isToolEnabled("users_list", ToolSource.MCP, "aap"))

        val result = router.getToolsForQuery("show hosts and users").tools
        val names = result.map { it.spec.name }

        assertFalse("list_hosts" in names)
        assertFalse("users_list" in names)
    }

    @Test
    fun `SHOULD keep manual disables after re-registering local tools`() {
        router.registerLocalTools(listOf(localTool("list_hosts"), localTool("list_inventories")))
        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)

        router.registerLocalTools(listOf(localTool("list_hosts"), localTool("list_inventories")))

        assertFalse(router.isToolEnabled("list_hosts", ToolSource.LOCAL))
    }

    @Test
    fun `SHOULD keep manual MCP disables alongside auto-disabled overlaps`() {
        val local = listOf(localTool("list_hosts"))
        val mcp = listOf(
            mcpTool("hosts_list"),
            mcpTool("users_list")
        )
        router.registerLocalTools(local)
        router.registerMcpTools(mcp)

        router.setToolEnabled("users_list", ToolSource.MCP, "aap", false)

        assertFalse(router.isToolEnabled("hosts_list", ToolSource.MCP, "aap"), "auto-disabled overlap")
        assertFalse(router.isToolEnabled("users_list", ToolSource.MCP, "aap"), "manually disabled")

        val result = router.getToolsForQuery("show hosts and users").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hosts" in names)
        assertFalse("hosts_list" in names)
        assertFalse("users_list" in names)
    }

    @Test
    fun `applyPersistedState SHOULD skip invalid key formats gracefully`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))

        router.applyPersistedState(setOf("INVALID:list_hosts", "list_hosts", ":list_hosts"), emptySet())

        assertTrue(router.isToolEnabled("list_hosts", ToolSource.LOCAL), "valid tool unaffected by invalid keys")
    }

    @Test
    fun `SHOULD re-include tool in query results WHEN re-enabled after disable`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories")
        )
        router.registerLocalTools(tools)
        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)

        val before = router.getToolsForQuery("show my hosts").tools
        assertFalse("list_hosts" in before.map { it.spec.name })

        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = true)

        val after = router.getToolsForQuery("show my hosts").tools
        assertTrue("list_hosts" in after.map { it.spec.name })
    }

    @Test
    fun `SHOULD return categoryMatched true but empty tools WHEN all category tools disabled`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories"),
            localTool("list_groups")
        )
        router.registerLocalTools(tools)
        router.setToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)
        router.setToolEnabled("list_inventories", ToolSource.LOCAL, enabled = false)
        router.setToolEnabled("list_groups", ToolSource.LOCAL, enabled = false)

        val result = router.getToolsForQuery("show my hosts")

        assertTrue(result.categoryMatched, "category should match")
        assertTrue(result.tools.isEmpty(), "tools should be empty")
    }

    // --- getCategoryForTool ---

    @Test
    fun `getCategoryForTool SHOULD return INVENTORY for list_hosts`() {
        assertEquals("INVENTORY", ToolRouter.getCategoryForTool("list_hosts"))
    }

    @Test
    fun `getCategoryForTool SHOULD return JOBS for launch_job`() {
        assertEquals("JOBS", ToolRouter.getCategoryForTool("launch_job"))
    }

    @Test
    fun `getCategoryForTool SHOULD return EDA for list_eda_activations`() {
        assertEquals("EDA", ToolRouter.getCategoryForTool("list_eda_activations"))
    }

    @Test
    fun `getCategoryForTool SHOULD return PLATFORM for list_platform_users`() {
        assertEquals("PLATFORM", ToolRouter.getCategoryForTool("list_platform_users"))
    }

    @Test
    fun `getCategoryForTool SHOULD return null for unknown tool`() {
        assertNull(ToolRouter.getCategoryForTool("nonexistent_tool"))
    }

    // --- Issue #288: user re-enable survives re-registration ---

    @Test
    fun `SHOULD preserve user re-enable of auto-disabled MCP tool after re-registration`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))
        assertFalse(router.isToolEnabled("hosts_list", ToolSource.MCP), "auto-disabled")

        router.setToolEnabled("hosts_list", ToolSource.MCP, "aap", true)
        assertTrue(router.isToolEnabled("hosts_list", ToolSource.MCP, "aap"), "user re-enabled")

        router.registerLocalTools(listOf(localTool("list_hosts")))
        assertTrue(router.isToolEnabled("hosts_list", ToolSource.MCP, "aap"), "survives re-registration")
    }

    @Test
    fun `userEnabled SHOULD override autoDisabled but not userDisabled`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))
        router.registerMcpTools(listOf(mcpTool("hosts_list"), mcpTool("users_list")))

        assertTrue(router.isAutoDisabled("hosts_list", ToolSource.MCP))
        assertFalse(router.isAutoDisabled("users_list", ToolSource.MCP))

        router.setToolEnabled("hosts_list", ToolSource.MCP, "aap", true)
        assertTrue(router.isToolEnabled("hosts_list", ToolSource.MCP, "aap"), "userEnabled overrides autoDisabled")

        router.setToolEnabled("users_list", ToolSource.MCP, "aap", false)
        assertFalse(router.isToolEnabled("users_list", ToolSource.MCP, "aap"), "userDisabled takes effect")
    }

    @Test
    fun `applyPersistedState SHOULD populate both sets correctly`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))
        router.registerMcpTools(listOf(mcpTool("hosts_list"), mcpTool("users_list")))

        router.applyPersistedState(
            disabled = setOf("MCP:aap:users_list"),
            enabledOverrides = setOf("MCP:aap:hosts_list")
        )

        assertTrue(router.isToolEnabled("hosts_list", ToolSource.MCP, "aap"), "enabledOverride overrides autoDisabled")
        assertFalse(router.isToolEnabled("users_list", ToolSource.MCP, "aap"), "userDisabled applied")
    }

    @Test
    fun `isAutoDisabled SHOULD return correct status`() {
        router.registerLocalTools(listOf(localTool("list_hosts")))
        router.registerMcpTools(listOf(mcpTool("hosts_list"), mcpTool("users_list")))

        assertTrue(router.isAutoDisabled("hosts_list", ToolSource.MCP))
        assertFalse(router.isAutoDisabled("users_list", ToolSource.MCP))
        assertFalse(router.isAutoDisabled("list_hosts", ToolSource.LOCAL))
    }

    @Test
    fun `getCategoryForTool SHOULD cover all local tool names in all categories`() {
        for (name in ALL_LOCAL_TOOL_NAMES) {
            assertNotNull(
                ToolRouter.getCategoryForTool(name),
                "Tool '$name' should have a category"
            )
        }
    }

    // --- OVERLAP_MAPPING validation (issue #332) ---

    @Test
    fun `OVERLAP_MAPPING values SHOULD use unprefixed tool names`() {
        val prefixPattern = Regex("^(controller|eda|gateway)\\.")
        val prefixedValues = ToolRouter.OVERLAP_MAPPING.values
            .flatMap { it }
            .filter { prefixPattern.containsMatchIn(it) }

        assertTrue(
            prefixedValues.isEmpty(),
            "OVERLAP_MAPPING should not contain prefixed names but found: $prefixedValues"
        )
    }

    @Test
    fun `OVERLAP_MAPPING values SHOULD use _retrieve not _read for get operations`() {
        val readSuffixed = ToolRouter.OVERLAP_MAPPING.values
            .flatMap { it }
            .filter { it.endsWith("_read") }

        assertTrue(
            readSuffixed.isEmpty(),
            "OVERLAP_MAPPING should use _retrieve not _read but found: $readSuffixed"
        )
    }

    @Test
    fun `OVERLAP_MAPPING SHOULD cover all local tools`() {
        val missing = ALL_LOCAL_TOOL_NAMES.filter { it !in ToolRouter.OVERLAP_MAPPING }
        assertTrue(
            missing.isEmpty(),
            "Every local tool should have an OVERLAP_MAPPING entry but missing: $missing"
        )
    }

    @Test
    fun `OVERLAP_MAPPING values SHOULD have no accidental duplicates`() {
        // These 7 MCP names intentionally map to both Controller and Platform/EDA local tools
        // because the aap-mcp-server uses the same unprefixed operationId across services.
        // See #342 for the design discussion on resolving these collisions.
        val knownDuplicates = setOf(
            "organizations_list", "users_list", "teams_list", "role_definitions_list",
            "projects_list", "credentials_list", "credential_types_list",
        )

        val allValues = ToolRouter.OVERLAP_MAPPING.values.flatMap { it }
        val seen = mutableMapOf<String, MutableList<String>>()
        for ((localName, mcpNames) in ToolRouter.OVERLAP_MAPPING) {
            for (mcpName in mcpNames) {
                seen.getOrPut(mcpName) { mutableListOf() }.add(localName)
            }
        }
        val unexpectedDuplicates = seen
            .filter { it.value.size > 1 && it.key !in knownDuplicates }
            .map { "${it.key} -> ${it.value}" }

        assertTrue(
            unexpectedDuplicates.isEmpty(),
            "Unexpected duplicate MCP names in OVERLAP_MAPPING: $unexpectedDuplicates"
        )
    }

    @Test
    fun `OVERLAP_MAPPING values SHOULD match verified aap-mcp-server tool names`() {
        val verifiedToolNames = setOf(
            // job_management
            "activation_instances_list", "activation_instances_logs_list", "analytics_retrieve",
            "job_templates_launch_create", "job_templates_launch_retrieve", "job_templates_list",
            "job_templates_retrieve", "jobs_cancel_create", "jobs_job_events_list",
            "jobs_job_host_summaries_list", "jobs_list", "jobs_relaunch_create",
            "jobs_relaunch_retrieve", "jobs_retrieve", "jobs_stdout_retrieve", "metrics_retrieve",
            "projects_list", "workflow_job_templates_launch_create", "workflow_job_templates_list",
            "workflow_job_templates_retrieve", "workflow_jobs_cancel_create", "workflow_jobs_list",
            "workflow_jobs_relaunch_create", "workflow_jobs_retrieve",
            "workflow_jobs_workflow_nodes_list",
            // inventory_management
            "groups_create", "groups_list", "hosts_list", "hosts_retrieve",
            "hosts_variable_data_retrieve", "inventories_list", "inventory_sources_update_create",
            // system_monitoring
            "activity_stream_list", "activity_stream_retrieve", "activitystream_list",
            "activitystream_retrieve", "authenticators_list", "feature_flags_state_retrieve",
            "instance_groups_create", "instance_groups_list", "instance_groups_retrieve",
            "instances_create", "instances_retrieve", "mesh_visualizer_retrieve", "status_retrieve",
            // user_management
            "authenticator_maps_create", "authenticator_maps_list",
            "authenticators_create", "authenticators_destroy",
            "authenticators_retrieve", "authenticators_update", "me_list",
            "organizations_create", "organizations_destroy", "organizations_list",
            "organizations_retrieve", "organizations_update", "role_definitions_list",
            "role_team_assignments_list", "role_user_assignments_create",
            "role_user_assignments_destroy", "role_user_assignments_list", "teams_create",
            "teams_destroy", "teams_list", "teams_retrieve", "teams_update", "teams_users_list",
            "users_create", "users_destroy", "users_list", "users_retrieve", "users_teams_list",
            "users_update",
            // security_compliance
            "credential_types_create", "credential_types_destroy", "credential_types_list",
            "credential_types_retrieve", "credential_types_update", "credentials_create",
            "credentials_list", "credentials_retrieve", "credentials_test_create",
            // platform_configuration
            "config_create", "config_retrieve", "execution_environments_create",
            "execution_environments_destroy", "execution_environments_list",
            "execution_environments_retrieve", "execution_environments_update",
            "notification_templates_create", "notification_templates_destroy",
            "notification_templates_list", "notification_templates_retrieve",
            "notification_templates_update", "settings_getter", "settings_list",
            "settings_partial_update", "settings_retrieve", "settings_update",
        )

        val mappedMcpNames = ToolRouter.OVERLAP_MAPPING.values.flatMap { it }.toSet()
        val notInEda = setOf(
            "audit_rules_list", "activations_list", "activations_retrieve",
            "rulebooks_list", "decision_environments_list", "event_streams_list",
        )
        val notYetExposed = setOf(
            "schedules_list", "schedules_partial_update", "schedules_update",
            "instances_list", "ping_retrieve", "inventory_sources_list",
            "labels_list", "roles_list", "applications_list", "tokens_list",
            "projects_retrieve", "job_templates_survey_spec_retrieve",
            "workflow_approvals_list", "workflow_approvals_approve_create",
            "workflow_approvals_deny_create", "services_list", "service_clusters_list",
            // Hub — no MCP server exists yet
            "collections_list", "namespaces_list", "collection_versions_list",
            "execution_environments_repositories_list", "execution_environments_registries_list",
            "hub_users_list", "hub_groups_list", "hub_role_definitions_list",
        )
        val verifiable = mappedMcpNames - notInEda - notYetExposed
        val notFound = verifiable.filter { it !in verifiedToolNames }

        assertTrue(
            notFound.isEmpty(),
            "OVERLAP_MAPPING values not matching verified server tools: $notFound"
        )
    }

    // --- Synonym expansion (#120 Tier 1) ---

    @Test
    fun `SHOULD match JOBS WHEN query uses deploy synonym`() {
        val tools = listOf(
            localTool("launch_job", destructive = true),
            localTool("list_job_templates"),
            localTool("list_hosts")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("deploy my application").tools
        val names = result.map { it.spec.name }

        assertTrue("launch_job" in names || "list_job_templates" in names)
        assertFalse("list_hosts" in names)
    }

    @Test
    fun `SHOULD match INVENTORY WHEN query uses workstation synonym for host`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories"),
            localTool("list_jobs")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery("list workstations").tools
        val names = result.map { it.spec.name }

        assertTrue("list_hosts" in names || "list_inventories" in names)
        assertFalse("list_jobs" in names)
    }

    // --- Description-based scoring (#120 Tier 1) ---

    @Test
    fun `SHOULD rank tool higher WHEN description matches query tokens`() {
        val withLabel = localTool(
            "list_job_templates",
            description = "List job templates with optional search and label filter"
        )
        val plain = localTool(
            "list_workflow_templates",
            description = "List workflow job templates"
        )
        router.registerLocalTools(listOf(withLabel, plain))

        val result = router.getToolsForQuery("filter templates by label").tools
        val names = result.map { it.spec.name }

        assertTrue(names.isNotEmpty())
        assertEquals("list_job_templates", names.first())
    }

    // --- Meta-search / no-match fallback (#120 Tier 1) ---

    @Test
    fun `SHOULD return search_available_tools WHEN query is tool discovery`() {
        val search = localTool("search_available_tools", description = "Search available tools")
        val hosts = localTool("list_hosts")
        router.registerLocalTools(listOf(search, hosts))

        val result = router.getToolsForQuery("what can you do?")
        assertFalse(result.categoryMatched)
        assertEquals(listOf("search_available_tools"), result.tools.map { it.spec.name })
    }

    @Test
    fun `SHOULD meta-search by description WHEN no category matches`() {
        val obscure = localTool(
            "list_notification_templates",
            description = "Manage alert smtp destinations and pagerduty channels"
        )
        val hosts = localTool("list_hosts", description = "List inventory hosts")
        router.registerLocalTools(listOf(obscure, hosts))

        // "smtp"/"pagerduty" are not category keywords; description search should still find the tool
        val result = router.getToolsForQuery("smtp pagerduty destinations")
        assertFalse(result.categoryMatched)
        assertTrue(result.tools.any { it.spec.name == "list_notification_templates" })
        assertFalse(result.tools.any { it.spec.name == "list_hosts" })
    }

    @Test
    fun `searchAvailableTools SHOULD score name and description`() {
        router.registerLocalTools(
            listOf(
                localTool("list_hosts", description = "List inventory hosts"),
                localTool(
                    "list_job_templates",
                    description = "List job templates with optional label filter"
                )
            )
        )

        val hits = router.searchAvailableTools("label filter", maxResults = 5)
        assertTrue(hits.any { it.spec.name == "list_job_templates" })
        assertEquals("list_job_templates", hits.first().spec.name)
    }

    // --- Deterministic ordering (#439) ---

    @Test
    fun `cherryPick SHOULD break score ties by tool name alphabetically`() {
        // Same name-overlap score for both list_ tools (+3 list boost); name secondary sort
        val tools = listOf(
            localTool("list_jobs"),
            localTool("list_job_templates"),
            localTool("get_job")
        )
        router.registerLocalTools(tools)

        val first = router.getToolsForQuery("show jobs").tools.map { it.spec.name }
        val second = router.getToolsForQuery("show jobs").tools.map { it.spec.name }

        assertEquals(first, second)
        // Among equal high-score list_ tools, alphabetical order must be stable
        val listTools = first.filter { it.startsWith("list_") }
        assertEquals(listTools.sorted(), listTools)
    }

    @Test
    fun `identical tool sets SHOULD produce identical ordering across queries`() {
        val tools = listOf(
            localTool("list_hosts"),
            localTool("list_inventories"),
            localTool("list_groups"),
            localTool("get_host_facts")
        )
        router.registerLocalTools(tools)

        val a = router.getToolsForQuery("show hosts inventory groups").tools.map { it.spec.name }
        val b = router.getToolsForQuery("show hosts inventory groups").tools.map { it.spec.name }
        assertEquals(a, b)
        assertTrue(a.size >= 2)
    }

    // --- AAP role filtering (#120 Tier 1) ---

    @Test
    fun `SHOULD filter destructive tools WHEN aapRole is AUDITOR`() {
        val tools = listOf(
            localTool("list_job_templates"),
            localTool("launch_job", destructive = true),
            localTool("list_jobs")
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery(
            "launch a job template",
            aapRole = AapRole.AUDITOR
        ).tools
        val names = result.map { it.spec.name }

        assertTrue("list_job_templates" in names || "list_jobs" in names)
        assertFalse("launch_job" in names)
    }

    @Test
    fun `SHOULD keep destructive tools WHEN aapRole is OPERATOR`() {
        val tools = listOf(
            localTool("list_job_templates"),
            localTool("launch_job", destructive = true)
        )
        router.registerLocalTools(tools)

        val result = router.getToolsForQuery(
            "launch a job template",
            aapRole = AapRole.OPERATOR
        ).tools
        assertTrue(result.any { it.spec.name == "launch_job" })
    }

    @Test
    fun `User toAapRole SHOULD map system auditor and superuser`() {
        assertEquals(
            AapRole.AUDITOR,
            User(1, "aud", isSystemAuditor = true).toAapRole()
        )
        assertEquals(
            AapRole.ADMIN,
            User(2, "admin", isSuperuser = true, isSystemAuditor = true).toAapRole()
        )
        assertEquals(
            AapRole.OPERATOR,
            User(3, "op").toAapRole()
        )
    }

    @Test
    fun `searchAvailableTools SHOULD honor AUDITOR from last getToolsForQuery context`() {
        router.registerLocalTools(
            listOf(
                localTool("list_job_templates"),
                localTool("launch_job", destructive = true)
            )
        )
        // Seed routing context with auditor role
        router.getToolsForQuery("list job templates", aapRole = AapRole.AUDITOR)

        val hits = router.searchAvailableTools("launch job")
        assertFalse(hits.any { it.spec.name == "launch_job" })
        assertTrue(hits.any { it.spec.name == "list_job_templates" } || hits.isEmpty())
    }

    @Test
    fun `searchAvailableTools SHOULD filter MCP write tools for AUDITOR`() {
        router.registerMcpTools(
            listOf(
                mcpTool("job_templates_list", toolset = "job_management"),
                mcpTool("job_templates_launch_create", toolset = "job_management")
            )
        )
        router.getToolsForQuery("list templates", aapRole = AapRole.AUDITOR)

        val hits = router.searchAvailableTools("launch create template")
        assertFalse(hits.any { it.spec.name.endsWith("_create") || it.spec.name.contains("launch") })
    }

    @Test
    fun `AapInstance toAapRole SHOULD map persisted flags`() {
        val auditor = io.github.leogallego.ansiblejane.model.AapInstance(
            id = "1", baseUrl = "https://aap.example", token = "t",
            isSystemAuditor = true
        )
        assertEquals(AapRole.AUDITOR, auditor.toAapRole())
    }
}
