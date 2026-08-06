package io.github.leogallego.ansiblejane.assistant.di

import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.assistant.tools.local.*
import io.github.leogallego.ansiblejane.network.createPlatformHttpClient
import io.github.leogallego.ansiblejane.network.mcp.McpServerManager
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val sharedAssistantModule = module {
    single {
        McpServerManager(
            ktorClientFactory = { instance, serverConfig ->
                createPlatformHttpClient(trustSelfSigned = instance.trustSelfSigned) {
                    defaultRequest {
                        if (serverConfig.useInstanceAuth) {
                            header(HttpHeaders.Authorization, "Bearer ${instance.token}")
                        }
                        serverConfig.headers
                            .filter { (key, _) ->
                                !serverConfig.useInstanceAuth ||
                                    !key.equals(HttpHeaders.Authorization, ignoreCase = true)
                            }
                            .forEach { (key, value) ->
                                header(key, value)
                            }
                    }
                    install(SSE)
                }
            }
        )
    }

    single(named("llm")) {
        createPlatformHttpClient {
            expectSuccess = false
        }
    }

    single {
        ToolRouter(
            initialLocalTools = getAll<LocalTool>(),
            repository = get()
        )
    }

    // Jobs & Templates
    single { ListJobTemplatesLocalTool(get()) } bind LocalTool::class
    single { LaunchJobLocalTool(get()) } bind LocalTool::class
    single { GetJobLocalTool(get()) } bind LocalTool::class
    single { GetJobStdoutLocalTool(get()) } bind LocalTool::class
    single { ListJobsLocalTool(get()) } bind LocalTool::class
    single { ListWorkflowTemplatesLocalTool(get()) } bind LocalTool::class
    single { LaunchWorkflowLocalTool(get()) } bind LocalTool::class
    single { GetWorkflowJobLocalTool(get()) } bind LocalTool::class
    single { ListSchedulesLocalTool(get()) } bind LocalTool::class
    single { ToggleScheduleLocalTool(get()) } bind LocalTool::class
    single { ListWorkflowNodesLocalTool(get()) } bind LocalTool::class
    single { GetSurveySpecLocalTool(get()) } bind LocalTool::class
    single { ListPendingApprovalsLocalTool(get()) } bind LocalTool::class
    single { ApproveWorkflowLocalTool(get()) } bind LocalTool::class
    single { DenyWorkflowLocalTool(get()) } bind LocalTool::class

    // Inventory
    single { ListInventoriesLocalTool(get()) } bind LocalTool::class
    single { ListHostsLocalTool(get()) } bind LocalTool::class
    single { GetHostFactsLocalTool(get()) } bind LocalTool::class
    single { GetHostJobSummariesLocalTool(get()) } bind LocalTool::class
    single { ListGroupsLocalTool(get()) } bind LocalTool::class
    single { ListInventorySourcesLocalTool(get()) } bind LocalTool::class
    single { ListLabelsLocalTool(get()) } bind LocalTool::class

    // Infrastructure & Monitoring
    single { ListInstancesLocalTool(get()) } bind LocalTool::class
    single { GetInstanceLocalTool(get()) } bind LocalTool::class
    single { ListInstanceGroupsLocalTool(get()) } bind LocalTool::class
    single { PingLocalTool(get()) } bind LocalTool::class
    single { GetMeshTopologyLocalTool(get()) } bind LocalTool::class

    // Security & Credentials
    single { ListCredentialsLocalTool(get()) } bind LocalTool::class
    single { GetCredentialLocalTool(get()) } bind LocalTool::class
    single { ListCredentialTypesLocalTool(get()) } bind LocalTool::class

    // Configuration
    single { ListProjectsLocalTool(get()) } bind LocalTool::class
    single { GetProjectLocalTool(get()) } bind LocalTool::class
    single { ListExecutionEnvironmentsLocalTool(get()) } bind LocalTool::class
    single { ListNotificationTemplatesLocalTool(get()) } bind LocalTool::class
    single { GetSettingsLocalTool(get()) } bind LocalTool::class
    single { GetConfigLocalTool(get()) } bind LocalTool::class

    // RBAC / Admin
    single { ListOrganizationsLocalTool(get()) } bind LocalTool::class
    single { ListUsersLocalTool(get()) } bind LocalTool::class
    single { ListTeamsLocalTool(get()) } bind LocalTool::class
    single { ListRolesLocalTool(get()) } bind LocalTool::class
    single { ListRoleDefinitionsLocalTool(get()) } bind LocalTool::class
    single { ListApplicationsLocalTool(get()) } bind LocalTool::class
    single { ListTokensLocalTool(get()) } bind LocalTool::class

    // Platform / Gateway
    single { ListPlatformOrganizationsLocalTool(get(), get()) } bind LocalTool::class
    single { ListPlatformUsersLocalTool(get(), get()) } bind LocalTool::class
    single { ListPlatformTeamsLocalTool(get(), get()) } bind LocalTool::class
    single { ListPlatformRoleDefinitionsLocalTool(get(), get()) } bind LocalTool::class
    single { ListAuthenticatorsLocalTool(get(), get()) } bind LocalTool::class
    single { ListPlatformServicesLocalTool(get(), get()) } bind LocalTool::class
    single { ListServiceClustersLocalTool(get(), get()) } bind LocalTool::class

    // EDA
    single { ListEdaAuditRulesLocalTool(get()) } bind LocalTool::class
    single { ListEdaActivationsLocalTool(get()) } bind LocalTool::class
    single { GetEdaActivationLocalTool(get()) } bind LocalTool::class
    single { ListEdaRulebooksLocalTool(get()) } bind LocalTool::class
    single { ListEdaDecisionEnvironmentsLocalTool(get()) } bind LocalTool::class
    single { ListEdaProjectsLocalTool(get()) } bind LocalTool::class
    single { ListEdaCredentialsLocalTool(get()) } bind LocalTool::class
    single { ListEdaCredentialTypesLocalTool(get()) } bind LocalTool::class
    single { ListEdaEventStreamsLocalTool(get()) } bind LocalTool::class
    single { ListEdaUsersLocalTool(get()) } bind LocalTool::class

    // Hub
    single { ListHubCollectionsLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubNamespacesLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubApprovalsLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubEeRepositoriesLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubEeRegistriesLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubUsersLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubGroupsLocalTool(get(), get()) } bind LocalTool::class
    single { ListHubRolesLocalTool(get(), get()) } bind LocalTool::class

    // Meta
    single { ListToolsLocalTool { get<ToolRouter>().getAllRegisteredTools() } } bind LocalTool::class
    single {
        SearchAvailableToolsLocalTool { query, maxResults ->
            get<ToolRouter>().searchAvailableTools(query, maxResults = maxResults)
        }
    } bind LocalTool::class
}
