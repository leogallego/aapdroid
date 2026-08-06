package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.*
import io.github.leogallego.ansiblejane.network.IAapApiProvider

class HubRepository(private val apiProvider: IAapApiProvider) : IHubRepository {

    override suspend fun getCollections(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<HubCollection>> = runV3Paginated {
        apiProvider.getHubApiService().getCollections(page, pageSize, search)
    }

    override suspend fun getNamespaces(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<HubNamespace>> = runV3Paginated {
        apiProvider.getHubApiService().getNamespaces(page, pageSize, search)
    }

    override suspend fun getCollectionVersions(
        page: Int,
        pageSize: Int,
        status: String?
    ): Result<ListResult<HubCollectionVersion>> = runV3Paginated {
        apiProvider.getHubApiService().getCollectionVersions(page, pageSize, status)
    }

    override suspend fun getEeRepositories(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<HubEeRepository>> = runV3Paginated {
        apiProvider.getHubApiService().getEeRepositories(page, pageSize, search)
    }

    override suspend fun getEeRegistries(
        page: Int,
        pageSize: Int,
        search: String?
    ): Result<ListResult<HubEeRegistry>> = runV3Paginated {
        apiProvider.getHubApiService().getEeRegistries(page, pageSize, search)
    }

    override suspend fun getUsers(
        page: Int,
        pageSize: Int
    ): Result<ListResult<HubUser>> = runV3Paginated {
        apiProvider.getHubApiService().getUsers(page, pageSize)
    }

    override suspend fun getGroups(
        page: Int,
        pageSize: Int
    ): Result<ListResult<HubGroup>> = runV3Paginated {
        apiProvider.getHubApiService().getGroups(page, pageSize)
    }

    override suspend fun getRoleDefinitions(
        page: Int,
        pageSize: Int
    ): Result<ListResult<HubRoleDefinition>> = runPaginated {
        apiProvider.getHubApiService().getRoleDefinitions(page, pageSize)
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
    } catch (e: Exception) {
        Result.failure(e)
    }

    private inline fun <T> runV3Paginated(
        block: () -> GalaxyV3Response<T>
    ): Result<ListResult<T>> = try {
        val response = block()
        Result.success(
            ListResult(
                items = response.data,
                hasMore = response.links.next != null,
                totalCount = response.meta.count
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }
}
