package io.github.leogallego.ansiblejane.presentation.workflows

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.leogallego.ansiblejane.fakes.FakeWorkflowRepository
import io.github.leogallego.ansiblejane.model.AppError
import io.github.leogallego.ansiblejane.model.WorkflowJobTemplateNode
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowTemplateDetailViewModelTest {

    private lateinit var fakeWorkflowRepo: FakeWorkflowRepository

    @BeforeTest
    fun setup() {
        setupMainDispatcher()
        fakeWorkflowRepo = FakeWorkflowRepository()
    }

    @AfterTest
    fun cleanup() {
        tearDownMainDispatcher()
    }

    private fun createViewModel(
        templateId: Int = 42,
        templateName: String = "Full%20Deploy",
    ): WorkflowTemplateDetailViewModel {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                "templateId" to templateId,
                "templateName" to templateName,
            )
        )
        return WorkflowTemplateDetailViewModel(savedStateHandle, fakeWorkflowRepo)
    }

    @Test
    fun `loads template nodes on init`() = runTest {
        fakeWorkflowRepo.templateNodes = listOf(
            WorkflowJobTemplateNode(id = 1, identifier = "step-1"),
            WorkflowJobTemplateNode(id = 2, identifier = "step-2"),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Full Deploy", viewModel.templateName)
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is WorkflowTemplateDetailUiState.Success)
            val success = state as WorkflowTemplateDetailUiState.Success
            assertEquals(2, success.nodes.size)
            assertEquals("step-1", success.nodes[0].identifier)
        }
    }

    @Test
    fun `invalid template id yields error without repo success`() = runTest {
        fakeWorkflowRepo.templateNodes = listOf(
            WorkflowJobTemplateNode(id = 1, identifier = "should-not-load"),
        )

        val viewModel = createViewModel(templateId = -1)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is WorkflowTemplateDetailUiState.Error)
            val error = state as WorkflowTemplateDetailUiState.Error
            assertTrue(error.error is AppError.Unknown)
        }
    }

    @Test
    fun `repository failure yields error state`() = runTest {
        fakeWorkflowRepo.shouldFail = true
        fakeWorkflowRepo.failureException = RuntimeException("Connection refused")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is WorkflowTemplateDetailUiState.Error)
        }
    }

    @Test
    fun `launch emits Launched with workflow job id`() = runTest {
        fakeWorkflowRepo.templateNodes = emptyList()
        fakeWorkflowRepo.launchResult = 77

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.launch()
        advanceUntilIdle()

        viewModel.launchState.test {
            val state = awaitItem()
            assertTrue(state is LaunchFromDetailState.Launched)
            assertEquals(77, (state as LaunchFromDetailState.Launched).workflowJobId)
        }
    }

    @Test
    fun `launch failure emits Failed`() = runTest {
        fakeWorkflowRepo.templateNodes = emptyList()
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeWorkflowRepo.shouldFail = true
        fakeWorkflowRepo.failureException = RuntimeException("Launch denied")
        viewModel.launch()
        advanceUntilIdle()

        viewModel.launchState.test {
            val state = awaitItem()
            assertTrue(state is LaunchFromDetailState.Failed)
            assertEquals("Launch denied", (state as LaunchFromDetailState.Failed).message)
        }
    }
}
