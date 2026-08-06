package io.github.leogallego.ansiblejane.assistant.presentation

import app.cash.turbine.test
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.data.TokenSavingMode
import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.Role
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.assistant.tools.ToolResult
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import io.github.leogallego.ansiblejane.fakes.FakeAssistantRepository
import io.github.leogallego.ansiblejane.fakes.FakeTokenManager
import io.github.leogallego.ansiblejane.fakes.FakeToolManifestRepository
import io.github.leogallego.ansiblejane.network.mcp.McpServerManager
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import io.github.leogallego.ansiblejane.testInstance
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    private lateinit var fakeAssistantRepo: FakeAssistantRepository
    private lateinit var fakeTokenManager: FakeTokenManager
    private lateinit var mcpServerManager: McpServerManager

    @BeforeTest
    fun setup() {
        setupMainDispatcher()
        fakeAssistantRepo = FakeAssistantRepository()
        fakeTokenManager = FakeTokenManager()
        mcpServerManager = McpServerManager(
            ktorClientFactory = { _, _ ->
                HttpClient(MockEngine) { engine { addHandler { respond("") } } }
            }
        )
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

    private fun createViewModel(localTools: List<LocalTool> = emptyList()) = AssistantViewModel(
        mcpServerManager = mcpServerManager,
        repository = fakeAssistantRepo,
        tokenManager = fakeTokenManager,
        manifestRepository = FakeToolManifestRepository(),
        toolRouter = ToolRouter(initialLocalTools = localTools, repository = fakeAssistantRepo),
        localTools = localTools,
    )

    @Test
    fun `init with no active instance emits Idle`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(AssistantUiState.Idle, awaitItem())
        }
    }

    @Test
    fun `init with active instance emits Active`() = runTest {
        fakeTokenManager.setInstances(listOf(testInstance))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is AssistantUiState.Active)
            assertTrue((state as AssistantUiState.Active).messages.isEmpty())
        }
    }

    @Test
    fun `sendMessage without LLM config adds guidance`() = runTest {
        fakeTokenManager.setInstances(listOf(testInstance))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("list hosts")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is AssistantUiState.Active)
            val messages = (state as AssistantUiState.Active).messages
            assertEquals(1, messages.size)
            assertEquals(Role.ASSISTANT, messages[0].role)
            assertTrue(messages[0].content.contains("configure an LLM provider"))
        }
    }

    @Test
    fun `TOOLS_ONLY general query short-circuits without tool execution`() = runTest {
        fakeTokenManager.setInstances(listOf(testInstance))
        fakeAssistantRepo.saveLlmConfig(
            LlmProviderConfig.OpenAiCompatible(
                url = "https://api.openai.com/v1",
                model = "gpt-4o",
                apiKey = "sk-test",
                tokenSavingMode = TokenSavingMode.TOOLS_ONLY,
            )
        )

        val viewModel = createViewModel(localTools = listOf(fakeLocalTool("list_hosts")))
        advanceUntilIdle()

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is AssistantUiState.Active)
            val active = state as AssistantUiState.Active
            assertFalse(active.isGenerating)
            assertTrue(active.messages.any { it.role == Role.USER && it.content == "hello" })
            assertTrue(
                active.messages.any {
                    it.role == Role.ASSISTANT && it.content.contains("query your AAP instance")
                }
            )
        }
    }

    @Test
    fun `clearHistory clears messages in Active state`() = runTest {
        fakeTokenManager.setInstances(listOf(testInstance))
        fakeAssistantRepo.addMessage(ChatMessage(role = Role.USER, content = "hi"))
        fakeAssistantRepo.addMessage(ChatMessage(role = Role.ASSISTANT, content = "hello"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.clearHistory()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is AssistantUiState.Active)
            assertTrue((state as AssistantUiState.Active).messages.isEmpty())
        }
    }
}
