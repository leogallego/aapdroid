package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.data.ExecutionEnvironmentListResult
import io.github.leogallego.ansiblejane.data.IProjectRepository
import io.github.leogallego.ansiblejane.data.ProjectListResult
import io.github.leogallego.ansiblejane.model.Project

class FakeProjectRepository : IProjectRepository {
    var projects = listOf<Project>()
    var projectCount: Int? = null
    var shouldFail = false
    var failureException: Exception = RuntimeException("Test error")

    override suspend fun getProjects(
        page: Int,
        pageSize: Int,
        search: String?,
    ): Result<ProjectListResult> {
        if (shouldFail) return Result.failure(failureException)
        return Result.success(
            ProjectListResult(
                projects = projects,
                hasMore = false,
                totalCount = projectCount ?: projects.size,
            )
        )
    }

    override suspend fun getProject(id: Int): Result<Project> {
        if (shouldFail) return Result.failure(failureException)
        return projects.find { it.id == id }?.let { Result.success(it) }
            ?: Result.failure(RuntimeException("Not found"))
    }

    override suspend fun getExecutionEnvironments(
        page: Int,
        pageSize: Int,
    ): Result<ExecutionEnvironmentListResult> {
        if (shouldFail) return Result.failure(failureException)
        return Result.success(ExecutionEnvironmentListResult(emptyList(), hasMore = false, totalCount = 0))
    }
}
