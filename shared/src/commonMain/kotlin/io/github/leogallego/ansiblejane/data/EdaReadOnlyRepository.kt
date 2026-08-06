package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.*
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import kotlin.coroutines.cancellation.CancellationException

class EdaReadOnlyRepository(private val apiProvider: IAapApiProvider) : IEdaReadOnlyRepository {

    override suspend fun getRulebooks(
        page: Int,
        pageSize: Int,
        name: String?
    ): Result<ListResult<EdaRulebook>> = runEdaPaginated {
        apiProvider.getEdaApiService().getRulebooks(page, pageSize, name)
    }

    override suspend fun getDecisionEnvironments(
        page: Int,
        pageSize: Int,
        name: String?
    ): Result<ListResult<EdaDecisionEnvironment>> = runEdaPaginated {
        apiProvider.getEdaApiService().getDecisionEnvironments(page, pageSize, name)
    }

    override suspend fun getProjects(
        page: Int,
        pageSize: Int,
        name: String?
    ): Result<ListResult<EdaProject>> = runEdaPaginated {
        apiProvider.getEdaApiService().getProjects(page, pageSize, name)
    }

    override suspend fun getCredentials(
        page: Int,
        pageSize: Int,
        name: String?
    ): Result<ListResult<EdaCredential>> = runEdaPaginated {
        apiProvider.getEdaApiService().getCredentials(page, pageSize, name)
    }

    override suspend fun getCredentialTypes(
        page: Int,
        pageSize: Int,
        name: String?
    ): Result<ListResult<EdaCredentialType>> = runEdaPaginated {
        apiProvider.getEdaApiService().getCredentialTypes(page, pageSize, name)
    }

    override suspend fun getEventStreams(
        page: Int,
        pageSize: Int,
        name: String?
    ): Result<ListResult<EdaEventStream>> = runEdaPaginated {
        apiProvider.getEdaApiService().getEventStreams(page, pageSize, name)
    }

    override suspend fun getUsers(
        page: Int,
        pageSize: Int
    ): Result<ListResult<EdaUser>> = runEdaPaginated {
        apiProvider.getEdaApiService().getUsers(page, pageSize)
    }

    private inline fun <T> runEdaPaginated(
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
