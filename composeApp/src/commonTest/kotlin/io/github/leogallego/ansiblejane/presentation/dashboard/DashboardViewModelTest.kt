package io.github.leogallego.ansiblejane.presentation.dashboard

import app.cash.turbine.test
import io.github.leogallego.ansiblejane.TestData
import io.github.leogallego.ansiblejane.fakes.FakeAapApiProvider
import io.github.leogallego.ansiblejane.fakes.FakeHostRepository
import io.github.leogallego.ansiblejane.fakes.FakeInventoryRepository
import io.github.leogallego.ansiblejane.fakes.FakeJobRepository
import io.github.leogallego.ansiblejane.fakes.FakeProjectRepository
import io.github.leogallego.ansiblejane.fakes.FakeScheduleRepository
import io.github.leogallego.ansiblejane.fakes.FakeTemplateRepository
import io.github.leogallego.ansiblejane.fakes.FakeTokenManager
import io.github.leogallego.ansiblejane.model.AppError
import io.github.leogallego.ansiblejane.model.JobTemplate
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
class DashboardViewModelTest {

    private lateinit var fakeJobRepo: FakeJobRepository
    private lateinit var fakeTemplateRepo: FakeTemplateRepository
    private lateinit var fakeInventoryRepo: FakeInventoryRepository
    private lateinit var fakeHostRepo: FakeHostRepository
    private lateinit var fakeProjectRepo: FakeProjectRepository
    private lateinit var fakeScheduleRepo: FakeScheduleRepository
    private lateinit var fakeApiProvider: FakeAapApiProvider
    private lateinit var fakeTokenManager: FakeTokenManager

    @BeforeTest
    fun setup() {
        setupMainDispatcher()
        fakeJobRepo = FakeJobRepository()
        fakeTemplateRepo = FakeTemplateRepository()
        fakeInventoryRepo = FakeInventoryRepository()
        fakeHostRepo = FakeHostRepository()
        fakeProjectRepo = FakeProjectRepository()
        fakeScheduleRepo = FakeScheduleRepository()
        fakeApiProvider = FakeAapApiProvider()
        fakeTokenManager = FakeTokenManager()
    }

    @AfterTest
    fun cleanup() {
        tearDownMainDispatcher()
    }

    private fun createViewModel() = DashboardViewModel(
        jobRepository = fakeJobRepo,
        templateRepository = fakeTemplateRepo,
        inventoryRepository = fakeInventoryRepo,
        hostRepository = fakeHostRepo,
        projectRepository = fakeProjectRepo,
        scheduleRepository = fakeScheduleRepo,
        apiProvider = fakeApiProvider,
        tokenManager = fakeTokenManager,
    )

    @Test
    fun `loads dashboard success when instance is active`() = runTest {
        fakeJobRepo.jobs = emptyList()
        fakeInventoryRepo.inventories = TestData.sampleInventories
        fakeHostRepo.hosts = TestData.sampleHosts
        fakeTemplateRepo.templates = listOf(JobTemplate(id = 1, name = "Deploy"))
        fakeProjectRepo.projectCount = 2
        fakeScheduleRepo.schedules = listOf(
            TestData.createSchedule(1).copy(nextRun = "2026-08-06T00:00:00Z"),
        )
        fakeTokenManager.setInstances(listOf(TestData.testInstance.copy(alias = "Prod")))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is DashboardUiState.Success)
            val success = state as DashboardUiState.Success
            assertEquals(HealthStatus.GREEN, success.healthStatus)
            assertEquals(3, success.inventoryCount)
            assertEquals(3, success.hostCount)
            assertEquals(1, success.templateCount)
            assertEquals(2, success.projectCount)
            assertNull(success.edaActivationsCount)
            assertEquals(1, success.upcomingSchedules.size)
            assertEquals("Prod", success.instanceAlias)
        }
    }

    @Test
    fun `job repository failure yields error state`() = runTest {
        fakeJobRepo.shouldFail = true
        fakeJobRepo.failureException = RuntimeException("Connection refused")
        fakeTokenManager.setInstances(listOf(TestData.testInstance))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is DashboardUiState.Error)
            assertTrue((state as DashboardUiState.Error).error is AppError.Unknown)
        }
    }

    @Test
    fun `stays loading when no active instance`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(DashboardUiState.Loading, awaitItem())
        }
    }
}
