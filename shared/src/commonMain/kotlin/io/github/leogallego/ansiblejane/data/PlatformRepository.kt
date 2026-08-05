package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.*
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import kotlin.coroutines.cancellation.CancellationException

class PlatformRepository(private val apiProvider: IAapApiProvider) : IPlatformRepository {

    override suspend fun getOrganizations(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<PlatformOrganization>> = runPaginated {
        apiProvider.getPlatformApiService().getOrganizations(page, pageSize, search)
    }

    override suspend fun getUsers(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<PlatformUser>> = runPaginated {
        apiProvider.getPlatformApiService().getUsers(page, pageSize, search)
    }

    override suspend fun getTeams(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<PlatformTeam>> = runPaginated {
        apiProvider.getPlatformApiService().getTeams(page, pageSize, search)
    }

    override suspend fun getRoleDefinitions(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<PlatformRoleDefinition>> = runPaginated {
        apiProvider.getPlatformApiService().getRoleDefinitions(page, pageSize, search)
    }

    override suspend fun getAuthenticators(
        page: Int,
        pageSize: Int
    ): Result<ListResult<PlatformAuthenticator>> = runPaginated {
        apiProvider.getPlatformApiService().getAuthenticators(page, pageSize)
    }

    override suspend fun getServices(
        page: Int,
        pageSize: Int
    ): Result<ListResult<PlatformService>> = runPaginated {
        apiProvider.getPlatformApiService().getServices(page, pageSize)
    }

    override suspend fun getServiceClusters(
        page: Int,
        pageSize: Int
    ): Result<ListResult<PlatformServiceCluster>> = runPaginated {
        apiProvider.getPlatformApiService().getServiceClusters(page, pageSize)
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
