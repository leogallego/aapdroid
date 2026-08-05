package io.github.leogallego.ansiblejane.presentation.hosts

import app.cash.turbine.test
import io.github.leogallego.ansiblejane.fakes.FakeHostRepository
import io.github.leogallego.ansiblejane.model.JobHostSummary
import io.github.leogallego.ansiblejane.model.JobHostSummaryFields
import io.github.leogallego.ansiblejane.model.JobSummaryRef
import io.github.leogallego.ansiblejane.setupMainDispatcher
import io.github.leogallego.ansiblejane.tearDownMainDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostDetailViewModelTest {

    private lateinit var fakeRepo: FakeHostRepository

    @BeforeTest
    fun setup() {
        setupMainDispatcher()
        fakeRepo = FakeHostRepository()
    }

    @AfterTest
    fun cleanup() {
        tearDownMainDispatcher()
    }

    @Test
    fun `loads facts and job summaries on init`() = runTest {
        fakeRepo.hostFacts = mapOf("ansible_os" to JsonPrimitive("Linux"))
        fakeRepo.jobSummaries = listOf(
            JobHostSummary(
                id = 10,
                job = 100,
                host = 1,
                ok = 5,
                created = "2026-01-01",
                summaryFields = JobHostSummaryFields(
                    job = JobSummaryRef(id = 100, name = "Deploy")
                )
            )
        )

        val viewModel = HostDetailViewModel(hostId = 1, hostRepository = fakeRepo)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is HostDetailUiState.Content)
            val content = state as HostDetailUiState.Content
            assertFalse(content.factsLoading)
            assertFalse(content.jobsLoading)
            assertEquals("Linux", (content.facts["ansible_os"] as JsonPrimitive).content)
            assertEquals(1, content.jobSummaries.size)
            assertEquals("Deploy", content.jobSummaries[0].summaryFields.job?.name)
        }
    }

    @Test
    fun `repository failures yield empty sections`() = runTest {
        fakeRepo.shouldFail = true

        val viewModel = HostDetailViewModel(hostId = 1, hostRepository = fakeRepo)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is HostDetailUiState.Content)
            val content = state as HostDetailUiState.Content
            assertFalse(content.factsLoading)
            assertFalse(content.jobsLoading)
            assertTrue(content.facts.isEmpty())
            assertTrue(content.jobSummaries.isEmpty())
        }
    }

    @Test
    fun `invalid host id skips repository calls`() = runTest {
        fakeRepo.hostFacts = mapOf("should_not_load" to JsonPrimitive("x"))

        val viewModel = HostDetailViewModel(hostId = 0, hostRepository = fakeRepo)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is HostDetailUiState.Content)
            val content = state as HostDetailUiState.Content
            assertFalse(content.factsLoading)
            assertFalse(content.jobsLoading)
            assertTrue(content.facts.isEmpty())
        }
    }
}
