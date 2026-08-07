package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import io.github.leogallego.ansiblejane.assistant.data.ModelFetcher
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.desktopTestKoinModule
import io.github.leogallego.ansiblejane.fakes.FakeAssistantRepository
import io.github.leogallego.ansiblejane.fakes.FakeAuthRepository
import io.github.leogallego.ansiblejane.fakes.FakeMcpConnectionRepository
import io.github.leogallego.ansiblejane.fakes.FakeTokenManager
import io.github.leogallego.ansiblejane.fakes.FakeToolManifestRepository
import io.github.leogallego.ansiblejane.fakes.FakeUserPreferencesRepository
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.presentation.settings.SettingsViewModel
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    private val fakeTokenManager = FakeTokenManager()
    private val fakeAuthRepository = FakeAuthRepository(fakeTokenManager)
    private val fakeUserPreferences = FakeUserPreferencesRepository()
    private val fakeAssistantRepo = FakeAssistantRepository()
    private val json = Json { ignoreUnknownKeys = true }
    private val mcpConnectionRepository = FakeMcpConnectionRepository()

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
    fun setUp() {
        setupMainDispatcher()
        startKoin { modules(desktopTestKoinModule(tokenManager = fakeTokenManager)) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        tearDownMainDispatcher()
    }

    private fun createViewModel() = SettingsViewModel(
        tokenManager = fakeTokenManager,
        authRepository = fakeAuthRepository,
        userPreferences = fakeUserPreferences,
        assistantRepository = fakeAssistantRepo,
        mcpConnectionRepository = mcpConnectionRepository,
        manifestRepository = FakeToolManifestRepository(),
        toolRouter = ToolRouter(initialLocalTools = emptyList(), repository = fakeAssistantRepo),
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

    private fun ComposeUiTest.setUpScreen(viewModel: SettingsViewModel = createViewModel()) {
        setContent {
            MaterialTheme {
                SettingsScreen(
                    onLogout = {},
                    onNavigateBack = {},
                    onAddInstance = {},
                    viewModel = viewModel
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun displays_instance_list_on_Instances_tab() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        setUpScreen()

        onNodeWithText("Instances").performClick()
        waitForIdle()

        onNodeWithText("Production").assertIsDisplayed()
        onNodeWithText("Staging").assertIsDisplayed()
    }

    @Test
    fun shows_Add_Instance_button_on_Instances_tab() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("Instances").performClick()
        waitForIdle()

        onNodeWithText("Add Instance").assertIsDisplayed()
    }

    @Test
    fun shows_Logout_All_button_on_Instances_tab() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("Instances").performClick()
        waitForIdle()

        onNodeWithText("Logout All").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun logout_all_shows_confirmation_dialog() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("Instances").performClick()
        waitForIdle()

        onNodeWithText("Logout All").performScrollTo().performClick()
        waitForIdle()

        onNodeWithText("Remove all AAP instances and log out?", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun back_button_present() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun about_section_shows_app_name_on_General_tab() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("Ansible Jane").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backup_restore_section_visible_on_General_tab() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("Backup & Restore is available on Android").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun active_instance_shows_Active_pill() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1, instance2))
        setUpScreen()

        onNodeWithText("Instances").performClick()
        waitForIdle()

        onNodeWithText("Active").assertIsDisplayed()
    }

    @Test
    fun tab_selector_shows_all_five_tabs() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("Instances").assertIsDisplayed()
        onNodeWithText("AI Provider").assertIsDisplayed()
        onNodeWithText("MCP Servers").assertIsDisplayed()
        onNodeWithText("Local Tools").assertIsDisplayed()
    }

    @Test
    fun AI_Provider_tab_shows_LLM_Provider_section() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("AI Provider").performClick()
        waitForIdle()

        onNodeWithText("LLM Provider").assertIsDisplayed()
    }

    @Test
    fun MCP_Servers_tab_shows_MCP_section() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("MCP Servers").performClick()
        waitForIdle()

        onNodeWithText("Enable AAP MCP").assertIsDisplayed()
    }

    @Test
    fun Local_Tools_tab_shows_tools_section() = runComposeUiTest {
        fakeTokenManager.setInstances(listOf(instance1))
        setUpScreen()

        onNodeWithText("Local Tools").performClick()
        waitForIdle()

        onNodeWithText("tools across", substring = true).assertIsDisplayed()
    }
}
