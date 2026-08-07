package io.github.leogallego.ansiblejane.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.data.IHostRepository
import io.github.leogallego.ansiblejane.data.IInventoryRepository
import io.github.leogallego.ansiblejane.data.IJobRepository
import io.github.leogallego.ansiblejane.data.IProjectRepository
import io.github.leogallego.ansiblejane.data.IScheduleRepository
import io.github.leogallego.ansiblejane.data.ITemplateRepository
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.model.AppError
import io.github.leogallego.ansiblejane.model.Job
import io.github.leogallego.ansiblejane.model.JobStatus
import io.github.leogallego.ansiblejane.data.IEdaActivationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class DashboardViewModel(
    private val jobRepository: IJobRepository,
    private val templateRepository: ITemplateRepository,
    private val inventoryRepository: IInventoryRepository,
    private val hostRepository: IHostRepository,
    private val projectRepository: IProjectRepository,
    private val scheduleRepository: IScheduleRepository,
    private val edaActivationRepository: IEdaActivationRepository,
    private val tokenManager: ITokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var dashboardJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            tokenManager.activeInstance
                .distinctUntilChangedBy { it?.id }
                .collect { instance ->
                    if (instance != null) loadDashboard()
                }
        }
        viewModelScope.launch {
            tokenManager.activeInstance
                .distinctUntilChangedBy { it?.instanceInfo }
                .collect { instance ->
                    if (instance != null) {
                        _uiState.update { current ->
                            if (current is DashboardUiState.Success) {
                                current.copy(
                                    instanceInfo = instance.instanceInfo,
                                    instanceUrl = instance.baseUrl,
                                    instanceAlias = instance.displayLabel,
                                )
                            } else current
                        }
                    }
                }
        }
    }

    fun refresh() {
        loadDashboard()
    }

    private fun loadDashboard() {
        dashboardJob?.cancel()
        _uiState.update { DashboardUiState.Loading }
        dashboardJob = viewModelScope.launch {
            val instance = tokenManager.activeInstance.value ?: return@launch
            val now = Clock.System.now()
            val since24h = (now - 24.hours).toString()
            val since7d = (now - 7.days).toString()

            val activeDeferred = async {
                jobRepository.getRecentJobs(
                    pageSize = 1,
                    statusFilters = setOf(JobStatus.RUNNING, JobStatus.PENDING, JobStatus.WAITING),
                )
            }
            val failedDeferred = async {
                jobRepository.getRecentJobs(
                    pageSize = 10,
                    statusFilters = setOf(JobStatus.FAILED, JobStatus.ERROR),
                    createdAfter = since24h,
                )
            }
            val successDeferred = async {
                jobRepository.getRecentJobs(
                    pageSize = 1,
                    statusFilters = setOf(JobStatus.SUCCESSFUL),
                    createdAfter = since24h,
                )
            }
            val inventoryDeferred = async {
                inventoryRepository.getInventories(pageSize = 1)
            }
            val hostDeferred = async {
                hostRepository.getAllHosts(pageSize = 1)
            }
            val templateDeferred = async {
                templateRepository.getTemplates(page = 1)
            }
            val projectDeferred = async {
                projectRepository.getProjects(pageSize = 1)
            }
            val edaDeferred = async {
                edaActivationRepository.getActivations(pageSize = 200).getOrNull()?.let { result ->
                    result.totalCount to result.activations.count { it.status == "running" }
                }
            }
            val jobHistoryDeferred = async {
                buildJobHistory7d(since7d)
            }
            val schedulesDeferred = async {
                scheduleRepository.getSchedules(pageSize = 5)
            }
            val activeResult = activeDeferred.await()
            val failedResult = failedDeferred.await()
            val successResult = successDeferred.await()

            val firstError = listOf(activeResult, failedResult, successResult)
                .firstOrNull { it.isFailure }

            if (firstError != null) {
                val exception = firstError.exceptionOrNull()
                    ?: IllegalStateException("Unknown dashboard error")
                _uiState.update { DashboardUiState.Error(AppError.from(exception)) }
                return@launch
            }

            val activeCount = activeResult.getOrThrow().totalCount
            val failedData = failedResult.getOrThrow()
            val successCount = successResult.getOrThrow().totalCount
            val inventoryCount = inventoryDeferred.await().getOrNull()?.totalCount ?: 0
            val hostCount = hostDeferred.await().getOrNull()?.totalCount ?: 0
            val templateCount = templateDeferred.await().getOrNull()?.totalCount ?: 0
            val projectCount = projectDeferred.await().getOrNull()?.totalCount ?: 0
            val edaData = edaDeferred.await()
            val jobHistory = jobHistoryDeferred.await()
            val schedules = schedulesDeferred.await().getOrNull()?.schedules
                ?.filter { it.enabled && it.nextRun != null }
                ?.take(3) ?: emptyList()
            val healthStatus = when {
                failedData.totalCount >= 4 -> HealthStatus.RED
                failedData.totalCount >= 1 -> HealthStatus.YELLOW
                else -> HealthStatus.GREEN
            }

            _uiState.update {
                DashboardUiState.Success(
                    activeJobsCount = activeCount,
                    failedCount24h = failedData.totalCount,
                    successfulCount24h = successCount,
                    recentFailures = failedData.jobs,
                    healthStatus = healthStatus,
                    inventoryCount = inventoryCount,
                    hostCount = hostCount,
                    templateCount = templateCount,
                    projectCount = projectCount,
                    edaActivationsCount = edaData?.first,
                    edaActiveRulebooksCount = edaData?.second,
                    jobHistory7d = jobHistory,
                    upcomingSchedules = schedules,
                    instanceInfo = instance?.instanceInfo,
                    instanceUrl = instance?.baseUrl,
                    instanceAlias = instance?.displayLabel,
                )
            }
        }
    }

    private suspend fun buildJobHistory7d(since7d: String): List<DayJobStats> {
        val successResult = jobRepository.getRecentJobs(
            pageSize = 200,
            statusFilters = setOf(JobStatus.SUCCESSFUL),
            createdAfter = since7d,
        )
        val failedResult = jobRepository.getRecentJobs(
            pageSize = 200,
            statusFilters = setOf(JobStatus.FAILED, JobStatus.ERROR),
            createdAfter = since7d,
        )

        val successJobs = successResult.getOrNull()?.jobs ?: emptyList()
        val failedJobs = failedResult.getOrNull()?.jobs ?: emptyList()

        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now()

        fun bucketByDate(jobs: List<Job>): Map<LocalDate, Int> =
            jobs.mapNotNull { job ->
                job.finished?.let {
                    try { Instant.parse(it).toLocalDateTime(tz).date } catch (_: Exception) { null }
                }
            }.groupingBy { it }.eachCount()

        val successByDate = bucketByDate(successJobs)
        val failedByDate = bucketByDate(failedJobs)

        return (6 downTo 0).map { daysAgo ->
            val dayDate = (now - daysAgo.days).toLocalDateTime(tz).date
            val label = dayDate.dayOfWeek.name.take(3).lowercase()
                .replaceFirstChar { it.uppercase() }
            DayJobStats(
                label = label,
                successful = successByDate[dayDate] ?: 0,
                failed = failedByDate[dayDate] ?: 0
            )
        }
    }

}
