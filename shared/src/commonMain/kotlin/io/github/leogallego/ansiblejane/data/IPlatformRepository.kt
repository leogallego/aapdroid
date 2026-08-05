package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.PlatformAuthenticator
import io.github.leogallego.ansiblejane.model.PlatformOrganization
import io.github.leogallego.ansiblejane.model.PlatformRoleDefinition
import io.github.leogallego.ansiblejane.model.PlatformService
import io.github.leogallego.ansiblejane.model.PlatformServiceCluster
import io.github.leogallego.ansiblejane.model.PlatformTeam
import io.github.leogallego.ansiblejane.model.PlatformUser

interface IPlatformRepository {
    suspend fun getOrganizations(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<PlatformOrganization>>

    suspend fun getUsers(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<PlatformUser>>

    suspend fun getTeams(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<PlatformTeam>>

    suspend fun getRoleDefinitions(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<PlatformRoleDefinition>>

    suspend fun getAuthenticators(
        page: Int = 1,
        pageSize: Int = 25,
    ): Result<ListResult<PlatformAuthenticator>>

    suspend fun getServices(
        page: Int = 1,
        pageSize: Int = 25,
    ): Result<ListResult<PlatformService>>

    suspend fun getServiceClusters(
        page: Int = 1,
        pageSize: Int = 25,
    ): Result<ListResult<PlatformServiceCluster>>
}
