package io.github.leogallego.ansiblejane.presentation.notifications

import app.cash.turbine.test
import io.github.leogallego.ansiblejane.fakes.FakeWorkflowRepository
import io.github.leogallego.ansiblejane.model.WorkflowApproval
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

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

    @Test
    fun `loads pending approvals on init`() = runTest {
        fakeWorkflowRepo.approvals = listOf(
            WorkflowApproval(id = 1, name = "Deploy to prod", status = "pending"),
            WorkflowApproval(id = 2, name = "Scale web", status = "pending"),
        )

        val viewModel = NotificationsViewModel(fakeWorkflowRepo)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(2, state.approvals.size)
            assertEquals(2, state.pendingCount)
            assertFalse(state.isLoading)
            assertNull(state.error)
        }
    }

    @Test
    fun `empty approvals yield zero pending count`() = runTest {
        fakeWorkflowRepo.approvals = emptyList()

        val viewModel = NotificationsViewModel(fakeWorkflowRepo)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.approvals.isEmpty())
            assertEquals(0, state.pendingCount)
            assertFalse(state.isLoading)
            assertNull(state.error)
        }
    }

    @Test
    fun `repository failure sets error message`() = runTest {
        fakeWorkflowRepo.shouldFail = true
        fakeWorkflowRepo.failureException = RuntimeException("Network error")

        val viewModel = NotificationsViewModel(fakeWorkflowRepo)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Network error", state.error)
        }
    }

    @Test
    fun `dismissApproval hides item across refreshIfStale reload`() = runTest {
        fakeWorkflowRepo.approvals = listOf(
            WorkflowApproval(id = 1, name = "Deploy to prod", status = "pending"),
            WorkflowApproval(id = 2, name = "Scale web", status = "pending"),
        )
        val viewModel = NotificationsViewModel(fakeWorkflowRepo)
        advanceUntilIdle()

        viewModel.dismissApproval(1)
        assertEquals(listOf(2), viewModel.uiState.value.approvals.map { it.id })

        // Stale reopen path must not resurrect a swiped-away approval.
        viewModel.refresh(clearSessionDismissals = false)
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.uiState.value.approvals.map { it.id })
        assertEquals(1, viewModel.uiState.value.pendingCount)
    }

    @Test
    fun `explicit refresh restores previously dismissed approvals`() = runTest {
        fakeWorkflowRepo.approvals = listOf(
            WorkflowApproval(id = 1, name = "Deploy to prod", status = "pending"),
            WorkflowApproval(id = 2, name = "Scale web", status = "pending"),
        )
        val viewModel = NotificationsViewModel(fakeWorkflowRepo)
        advanceUntilIdle()

        viewModel.dismissApproval(1)
        assertEquals(1, viewModel.uiState.value.approvals.size)

        viewModel.refresh() // default clears session dismissals
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.uiState.value.approvals.map { it.id })
    }
}
