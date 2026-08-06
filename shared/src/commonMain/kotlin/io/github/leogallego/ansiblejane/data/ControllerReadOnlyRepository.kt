package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.*
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException

class ControllerReadOnlyRepository(private val apiProvider: IAapApiProvider) : IControllerReadOnlyRepository {

    override suspend fun getOrganizations(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<Organization>> = runPaginated {
        apiProvider.getApiService().getOrganizations(page, pageSize, search)
    }

    override suspend fun getUsers(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<User>> = runPaginated {
        apiProvider.getApiService().getUsers(page, pageSize, search)
    }

    override suspend fun getTeams(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<Team>> = runPaginated {
        apiProvider.getApiService().getTeams(page, pageSize, search)
    }

    override suspend fun getRoles(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<Role>> = runPaginated {
        apiProvider.getApiService().getRoles(page, pageSize, search)
    }

    override suspend fun getRoleDefinitions(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<RoleDefinition>> = runPaginated {
        apiProvider.getApiService().getRoleDefinitions(page, pageSize, search)
    }

    override suspend fun getGroups(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<Group>> = runPaginated {
        apiProvider.getApiService().getGroups(page, pageSize, search)
    }

    override suspend fun getInventorySources(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<InventorySource>> = runPaginated {
        apiProvider.getApiService().getInventorySources(page, pageSize, search)
    }

    override suspend fun getLabels(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<Label>> = runPaginated {
        apiProvider.getApiService().getLabels(page, pageSize, search)
    }

    override suspend fun getCredentialTypes(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<CredentialType>> = runPaginated {
        apiProvider.getApiService().getCredentialTypes(page, pageSize, search)
    }

    override suspend fun getNotificationTemplates(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<NotificationTemplate>> = runPaginated {
        apiProvider.getApiService().getNotificationTemplates(page, pageSize, search)
    }

    override suspend fun getApplications(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<Application>> = runPaginated {
        apiProvider.getApiService().getApplications(page, pageSize, search)
    }

    override suspend fun getTokens(
        page: Int,
        pageSize: Int
    ): Result<ListResult<AapToken>> = runPaginated {
        apiProvider.getApiService().getTokens(page, pageSize)
    }

    override suspend fun getSettings(): Result<JsonElement> = try {
        Result.success(apiProvider.getApiService().getSettings())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun getConfig(): Result<JsonElement> = try {
        Result.success(apiProvider.getApiService().getConfig())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun getWorkflowJobTemplateNodes(
        page: Int,
        pageSize: Int,
        workflowJobTemplate: Int?
    ): Result<ListResult<WorkflowJobTemplateNode>> = runPaginated {
        apiProvider.getApiService().getWorkflowJobTemplateNodes(page, pageSize, workflowJobTemplate)
    }

    override suspend fun getSurveySpec(id: Int): Result<SurveySpec> = try {
        Result.success(apiProvider.getApiService().getSurveySpec(id))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private inline fun <T> runPaginated(
        block: () -> PaginatedResponse<T>
    ): Result<ListResult<T>> = try {
        val response = block()
        Result.success(
            ListResult(
                items = response.results,
                hasMore = response.next != null,
                totalCount = response.count
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

data class ListResult<T>(
    val items: List<T>,
    val hasMore: Boolean,
    val totalCount: Int
)
