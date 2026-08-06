package io.github.leogallego.ansiblejane.presentation.main

import app.cash.turbine.test
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.Role
import io.github.leogallego.ansiblejane.fakes.FakeAssistantRepository
import io.github.leogallego.ansiblejane.fakes.FakeTokenManager
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var fakeTokenManager: FakeTokenManager
    private lateinit var fakeAssistantRepo: FakeAssistantRepository

    private val instance = AapInstance(
        id = "inst-1",
        baseUrl = "https://aap.example.com",
        token = "token-1",
        alias = "Lab",
    )

    @BeforeTest
    fun setup() {
        setupMainDispatcher()
        fakeTokenManager = FakeTokenManager()
        fakeAssistantRepo = FakeAssistantRepository()
    }

    @AfterTest
    fun cleanup() {
        tearDownMainDispatcher()
    }

    private fun createViewModel() = MainViewModel(fakeTokenManager, fakeAssistantRepo)

    @Test
    fun `exposes active instance from token manager`() = runTest {
        fakeTokenManager.setInstances(listOf(instance))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Lab", state.activeInstance?.alias)
            assertEquals("inst-1", state.activeInstance?.id)
        }
    }

    @Test
    fun `switchActiveProvider updates active provider key`() = runTest {
        val config = LlmProviderConfig.OpenAiCompatible(
            url = "https://api.openai.com/v1",
            apiKey = "sk-test",
            model = "gpt-4o",
        )
        fakeAssistantRepo.saveLlmConfig(config)
        fakeAssistantRepo.saveAllLlmConfigs(mapOf("OPENAI" to config, "GEMINI" to config.copy(url = "https://generativelanguage.googleapis.com")))

        val viewModel = createViewModel()
        viewModel.switchActiveProvider("GEMINI")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("GEMINI", state.activeProviderKey)
        }
    }

    @Test
    fun `clearHistory clears assistant history`() = runTest {
        fakeAssistantRepo.addMessage(ChatMessage(role = Role.USER, content = "hello"))
        assertTrue(fakeAssistantRepo.getHistory().isNotEmpty())

        val viewModel = createViewModel()
        viewModel.clearHistory()

        assertTrue(fakeAssistantRepo.getHistory().isEmpty())
        assertEquals(0, viewModel.uiState.value.sessionTokens)
    }

    @Test
    fun `starts with null active instance when logged out`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.activeInstance)
            assertTrue(state.savedConfigs.isEmpty())
        }
    }
}
