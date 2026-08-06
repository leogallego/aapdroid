package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.HubCollection
import io.github.leogallego.ansiblejane.model.HubCollectionVersion
import io.github.leogallego.ansiblejane.model.HubEeRegistry
import io.github.leogallego.ansiblejane.model.HubEeRepository
import io.github.leogallego.ansiblejane.model.HubGroup
import io.github.leogallego.ansiblejane.model.HubNamespace
import io.github.leogallego.ansiblejane.model.HubRoleDefinition
import io.github.leogallego.ansiblejane.model.HubUser

interface IHubRepository {
    suspend fun getCollections(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
    ): Result<ListResult<HubCollection>>

    suspend fun getNamespaces(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
    ): Result<ListResult<HubNamespace>>

    suspend fun getCollectionVersions(
        page: Int = 1,
        pageSize: Int = 20,
        status: String? = null,
    ): Result<ListResult<HubCollectionVersion>>

    suspend fun getEeRepositories(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
    ): Result<ListResult<HubEeRepository>>

    suspend fun getEeRegistries(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
    ): Result<ListResult<HubEeRegistry>>

    suspend fun getUsers(
        page: Int = 1,
        pageSize: Int = 20,
    ): Result<ListResult<HubUser>>

    suspend fun getGroups(
        page: Int = 1,
        pageSize: Int = 20,
    ): Result<ListResult<HubGroup>>

    suspend fun getRoleDefinitions(
        page: Int = 1,
        pageSize: Int = 20,
    ): Result<ListResult<HubRoleDefinition>>
}
