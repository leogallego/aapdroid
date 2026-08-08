package io.github.leogallego.ansiblejane.presentation.settings

import app.cash.turbine.test
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.data.ModelFetcher
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelDownloadUiState
import io.github.leogallego.ansiblejane.presentation.settings.SettingsUiState
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.assistant.tools.ToolResult
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.fakes.FakeAssistantRepository
import io.github.leogallego.ansiblejane.fakes.FakeAuthRepository
import io.github.leogallego.ansiblejane.fakes.FakeLocalModelRepository
import io.github.leogallego.ansiblejane.fakes.FakeMcpConnectionRepository
import io.github.leogallego.ansiblejane.fakes.FakeTokenManager
import io.github.leogallego.ansiblejane.fakes.FakeUserPreferencesRepository
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import io.github.leogallego.ansiblejane.ui.components.ThemeMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var fakeTokenManager: FakeTokenManager
    private lateinit var fakeAuthRepository: FakeAuthRepository
    private lateinit var fakeUserPreferences: FakeUserPreferencesRepository
    private lateinit var fakeAssistantRepo: FakeAssistantRepository
    private lateinit var fakeLocalModelRepo: FakeLocalModelRepository
    private lateinit var mcpConnectionRepository: FakeMcpConnectionRepository
    private val json = Json { ignoreUnknownKeys = true }

    private val instance1 = AapInstance(
        id = "inst-1",
        baseUrl = "https://aap1.example.com",
        token = "token-1",
        alias = "Production"
    )

    private val instance2 = AapInstance(
        id = "inst-2",
        baseUrl = "https://aap2.example.com",
        token = "token-2",
        alias = "Staging"
    )

    @BeforeTest
    fun setup() {
        setupMainDispatcher()
        fakeTokenManager = FakeTokenManager()
        fakeAuthRepository = FakeAuthRepository(fakeTokenManager)
        fakeUserPreferences = FakeUserPreferencesRepository()
        fakeAssistantRepo = FakeAssistantRepository()
        fakeLocalModelRepo = FakeLocalModelRepository()
        mcpConnectionRepository = FakeMcpConnectionRepository()
    }

    @AfterTest
    fun cleanup() {
        tearDownMainDispatcher()
    }

    private fun fakeLocalTool(name: String) = object : LocalTool {
        override val spec = ToolSpec(name, "Description of $name", JsonObject(emptyMap()))
        override val isDestructive = false
        override suspend fun execute(args: JsonObject) = ToolResult(success = true)
    }

    private fun createViewModel(
        localTools: List<LocalTool> = emptyList(),
        localModelRepository: FakeLocalModelRepository = fakeLocalModelRepo,
    ) = SettingsViewModel(
        tokenManager = fakeTokenManager,
        authRepository = fakeAuthRepository,
        userPreferences = fakeUserPreferences,
        assistantRepository = fakeAssistantRepo,
        mcpConnectionRepository = mcpConnectionRepository,
        manifestRepository = io.github.leogallego.ansiblejane.fakes.FakeToolManifestRepository(),
        toolRouter = ToolRouter(initialLocalTools = localTools, repository = fakeAssistantRepo),
        localModelRepository = localModelRepository,
        json = json,
        modelFetcher = ModelFetcher(json) { _ ->
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = """{"data":[]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
        }
    )

    @Test
    fun `init emits Ready with instances from TokenManager`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.Ready)
        val ready = state as SettingsUiState.Ready
        assertEquals(2, ready.instances.size)
        assertEquals("Production", ready.instances[0].alias)
        assertEquals("Staging", ready.instances[1].alias)
    }

    @Test
    fun `init with empty instances emits Ready with empty list`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.Ready)
        val ready = state as SettingsUiState.Ready
        assertTrue(ready.instances.isEmpty())
        assertNull(ready.selectedInstance)
    }

    @Test
    fun `init sets active instance as selectedInstance`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        fakeTokenManager.setActiveInstanceDirect(instance2)

        val viewModel = createViewModel()

        val state = viewModel.uiState.value as SettingsUiState.Ready
        assertEquals("inst-2", state.selectedInstance?.id)
    }

    @Test
    fun `switchInstance updates active instance`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertEquals("inst-1", initial.selectedInstance?.id)

            viewModel.switchInstance("inst-2")

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals("inst-2", updated.selectedInstance?.id)
        }
    }

    @Test
    fun `removeInstance removes instance and evicts from API provider`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertEquals(2, initial.instances.size)

            viewModel.removeInstance("inst-1")

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals(1, updated.instances.size)
            assertEquals("inst-2", updated.instances[0].id)
            assertEquals(listOf("inst-1"), fakeAuthRepository.evictedInstances)
        }
    }

    @Test
    fun `removeInstance with active instance switches to remaining`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.removeInstance("inst-1")

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals("inst-2", updated.selectedInstance?.id)
        }
    }

    @Test
    fun `showInstanceDetails sets selectedInstanceForDetails`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        val viewModel = createViewModel()

        val initial = viewModel.uiState.value as SettingsUiState.Ready
        assertNull(initial.selectedInstanceForDetails)

        viewModel.showInstanceDetails("inst-2")

        val updated = viewModel.uiState.value as SettingsUiState.Ready
        assertEquals("inst-2", updated.selectedInstanceForDetails?.id)
        assertEquals("Staging", updated.selectedInstanceForDetails?.alias)
    }

    @Test
    fun `showInstanceDetails with unknown ID sets null`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1))
        val viewModel = createViewModel()

        viewModel.showInstanceDetails("unknown-id")

        val state = viewModel.uiState.value as SettingsUiState.Ready
        assertNull(state.selectedInstanceForDetails)
    }

    @Test
    fun `dismissDetails clears selectedInstanceForDetails`() = runTest {
        fakeTokenManager.setInstances(listOf(instance1))
        val viewModel = createViewModel()

        viewModel.showInstanceDetails("inst-1")
        val withDetails = viewModel.uiState.value as SettingsUiState.Ready
        assertEquals("inst-1", withDetails.selectedInstanceForDetails?.id)

        viewModel.dismissDetails()
        val dismissed = viewModel.uiState.value as SettingsUiState.Ready
        assertNull(dismissed.selectedInstanceForDetails)
    }

    @Test
    fun `dismissDetails when no details shown is a no-op`() = runTest {
        val viewModel = createViewModel()

        val before = viewModel.uiState.value
        viewModel.dismissDetails()
        val after = viewModel.uiState.value

        assertTrue(before is SettingsUiState.Ready)
        assertTrue(after is SettingsUiState.Ready)
        assertNull((after as SettingsUiState.Ready).selectedInstanceForDetails)
    }

    // --- Tool management ---

    @Test
    fun `init SHOULD load pre-existing disabled tools from repository`() = runTest {
        fakeAssistantRepo.savedDisabledTools = setOf("LOCAL:list_hosts", "MCP:aap:jobs_list")
        val tools = listOf(fakeLocalTool("list_hosts"), fakeLocalTool("list_inventories"))
        val viewModel = createViewModel(localTools = tools)

        viewModel.uiState.test {
            val state = awaitItem() as SettingsUiState.Ready
            val hostTool = state.localTools.find { it.name == "list_hosts" }
            assertFalse(hostTool?.isEnabled ?: true, "list_hosts should be disabled")
            val invTool = state.localTools.find { it.name == "list_inventories" }
            assertTrue(invTool?.isEnabled ?: false, "list_inventories should be enabled")
        }
    }

    @Test
    fun `toggleToolEnabled SHOULD disable a local tool and persist`() = runTest {
        val tools = listOf(fakeLocalTool("list_hosts"))
        val viewModel = createViewModel(localTools = tools)
        viewModel.uiState.test {
            skipItems(1)
            viewModel.toggleToolEnabled("list_hosts", ToolSource.LOCAL, enabled = false)
            val state = awaitItem() as SettingsUiState.Ready
            assertFalse(state.localTools.find { it.name == "list_hosts" }?.isEnabled ?: true)
            assertTrue("LOCAL:list_hosts" in fakeAssistantRepo.savedDisabledTools)
        }
    }

    @Test
    fun `toggleToolEnabled SHOULD re-enable a previously disabled tool`() = runTest {
        fakeAssistantRepo.savedDisabledTools = setOf("LOCAL:list_hosts")
        val tools = listOf(fakeLocalTool("list_hosts"))
        val viewModel = createViewModel(localTools = tools)
        viewModel.uiState.test {
            skipItems(1)
            viewModel.toggleToolEnabled("list_hosts", ToolSource.LOCAL, enabled = true)
            val state = awaitItem() as SettingsUiState.Ready
            assertTrue(state.localTools.find { it.name == "list_hosts" }?.isEnabled ?: false)
            assertFalse("LOCAL:list_hosts" in fakeAssistantRepo.savedDisabledTools)
        }
    }

    @Test
    fun `toggleToolEnabled SHOULD use MCP prefix for MCP tools`() = runTest {
        val viewModel = createViewModel()
        viewModel.toggleToolEnabled("hosts_list", ToolSource.MCP, "aap", false)
        assertTrue("MCP:aap:hosts_list" in fakeAssistantRepo.savedDisabledTools)
    }

    @Test
    fun `toggleExpandCategory SHOULD toggle category in expandedCategories`() = runTest {
        val viewModel = createViewModel()
        viewModel.uiState.test {
            skipItems(1)
            viewModel.toggleExpandCategory("JOBS")
            val expanded = awaitItem() as SettingsUiState.Ready
            assertTrue("JOBS" in expanded.expandedCategories)
            viewModel.toggleExpandCategory("JOBS")
            val collapsed = awaitItem() as SettingsUiState.Ready
            assertFalse("JOBS" in collapsed.expandedCategories)
        }
    }

    @Test
    fun `toggleExpandMcpServer SHOULD toggle server in expandedMcpServers`() = runTest {
        val viewModel = createViewModel()
        viewModel.uiState.test {
            skipItems(1)
            viewModel.toggleExpandMcpServer("Jobs")
            val expanded = awaitItem() as SettingsUiState.Ready
            assertTrue("Jobs" in expanded.expandedMcpServers)
        }
    }

    // --- Combined flow propagation ---

    @Test
    fun `themeMode flow updates propagate to UI state`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertEquals(ThemeMode.SYSTEM, initial.themeMode)

            fakeUserPreferences.setThemeMode(ThemeMode.DARK)

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals(ThemeMode.DARK, updated.themeMode)
        }
    }

    @Test
    fun `activeProviderKey flow updates propagate to UI state`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertNull(initial.activeProviderKey)

            fakeAssistantRepo.switchActiveProvider("openai")

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals("openai", updated.activeProviderKey)
        }
    }

    @Test
    fun `savedConfigs flow updates propagate to UI state`() = runTest {
        val viewModel = createViewModel()
        val testConfig = LlmProviderConfig.OpenAiCompatible(
            url = "https://api.openai.com/v1",
            model = "gpt-4",
            apiKey = "test-key"
        )

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertTrue(initial.savedConfigs.isEmpty())

            fakeAssistantRepo.saveAllLlmConfigs(mapOf("openai" to testConfig))

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals(1, updated.savedConfigs.size)
            assertEquals(testConfig, updated.savedConfigs["openai"])
        }
    }

    @Test
    fun `activeConfig flow updates propagate to UI state`() = runTest {
        val testConfig = LlmProviderConfig.OpenAiCompatible(
            url = "https://api.openai.com/v1",
            model = "gpt-4",
            apiKey = "test-key"
        )
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertNull(initial.activeConfig)

            fakeAssistantRepo.saveLlmConfig(testConfig)

            // saveLlmConfig updates 3 flows (activeConfig, savedConfigs, activeProviderKey).
            // The ViewModel's combine operator may emit intermediate states as each flow
            // settles — use expectMostRecentItem() to skip those and assert the final state.
            val updated = expectMostRecentItem() as SettingsUiState.Ready
            assertEquals(testConfig, updated.activeConfig)
        }
    }

    @Test
    fun `multiple simultaneous flow updates settle to correct final state`() = runTest {
        val testConfig = LlmProviderConfig.OpenAiCompatible(
            url = "https://api.openai.com/v1",
            model = "gpt-4",
            apiKey = "test-key"
        )
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // initial

            fakeUserPreferences.setThemeMode(ThemeMode.DARK)
            fakeAssistantRepo.switchActiveProvider("openai")
            fakeUserPreferences.setTimezoneId("America/New_York")
            fakeAssistantRepo.saveAllLlmConfigs(mapOf("openai" to testConfig))

            val settled = expectMostRecentItem() as SettingsUiState.Ready
            assertEquals(ThemeMode.DARK, settled.themeMode)
            assertEquals("openai", settled.activeProviderKey)
            assertEquals("America/New_York", settled.timezoneId)
            assertEquals(1, settled.savedConfigs.size)
        }
    }

    @Test
    fun `timezone flow updates propagate to UI state`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem() as SettingsUiState.Ready
            assertNull(initial.timezoneId)

            fakeUserPreferences.setTimezoneId("America/New_York")

            val updated = awaitItem() as SettingsUiState.Ready
            assertEquals("America/New_York", updated.timezoneId)
        }
    }

    // --- On-device local models ---

    @Test
    fun `downloadLocalModel delegates to repository and marks ready`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val modelId = "gemma-4-e4b-it"

        viewModel.downloadLocalModel(modelId)
        advanceUntilIdle()

        assertEquals(listOf(modelId), fakeLocalModelRepo.downloadCalls)
        val ready = assertIs<SettingsUiState.Ready>(viewModel.uiState.value)
        assertTrue(modelId in ready.localReadyIds)
        assertIs<LocalModelDownloadUiState.Succeeded>(ready.localDownloadState)
    }

    @Test
    fun `cancelLocalModelDownload delegates to repository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.cancelLocalModelDownload()
        advanceUntilIdle()

        assertEquals(1, fakeLocalModelRepo.cancelCalls)
        val ready = assertIs<SettingsUiState.Ready>(viewModel.uiState.value)
        assertIs<LocalModelDownloadUiState.Idle>(ready.localDownloadState)
    }

    @Test
    fun `deleteLocalModel removes readiness`() = runTest {
        val readyRepo = FakeLocalModelRepository(readyIds = setOf("gemma-4-e4b-it"))
        val viewModel = createViewModel(localModelRepository = readyRepo)
        advanceUntilIdle()
        val before = assertIs<SettingsUiState.Ready>(viewModel.uiState.value)
        assertTrue("gemma-4-e4b-it" in before.localReadyIds)

        viewModel.deleteLocalModel("gemma-4-e4b-it")
        advanceUntilIdle()

        assertEquals(listOf("gemma-4-e4b-it"), readyRepo.deleteCalls)
        val after = assertIs<SettingsUiState.Ready>(viewModel.uiState.value)
        assertFalse("gemma-4-e4b-it" in after.localReadyIds)
    }

    @Test
    fun `selectLocalModel saves OnDevice config and activates LOCAL`() = runTest {
        val viewModel = createViewModel()

        viewModel.selectLocalModel("gemma-4-e4b-it")
        advanceUntilIdle()

        val saved = fakeAssistantRepo.allConfigs[KnownProvider.LOCAL.name]
        assertIs<LlmProviderConfig.OnDevice>(saved)
        assertEquals("gemma-4-e4b-it", saved.modelId)
        assertEquals(4_096, saved.contextTokens)
        assertEquals(KnownProvider.LOCAL.name, fakeAssistantRepo.activeProvider)
    }

    @Test
    fun `setLocalModelContextTokens persists and updates active OnDevice`() = runTest {
        val viewModel = createViewModel()

        viewModel.selectLocalModel("gemma-4-e4b-it")
        advanceUntilIdle()
        viewModel.setLocalModelContextTokens("gemma-4-e4b-it", 16_384)
        advanceUntilIdle()

        assertEquals(16_384, fakeAssistantRepo.getModelContextTokens("gemma-4-e4b-it"))
        val active = fakeAssistantRepo.allConfigs[KnownProvider.LOCAL.name]
        assertIs<LlmProviderConfig.OnDevice>(active)
        assertEquals(16_384, active.contextTokens)
        val ready = assertIs<SettingsUiState.Ready>(viewModel.uiState.value)
        assertEquals(16_384, ready.localModelContextTokens["gemma-4-e4b-it"])
    }

    @Test
    fun `selectLocalModel copies stored context tokens into OnDevice`() = runTest {
        fakeAssistantRepo.setModelContextTokens("gemma-4-e4b-it", 8_192)
        val viewModel = createViewModel()

        viewModel.selectLocalModel("gemma-4-e4b-it")
        advanceUntilIdle()

        val saved = fakeAssistantRepo.allConfigs[KnownProvider.LOCAL.name]
        assertIs<LlmProviderConfig.OnDevice>(saved)
        assertEquals(8_192, saved.contextTokens)
    }
}
