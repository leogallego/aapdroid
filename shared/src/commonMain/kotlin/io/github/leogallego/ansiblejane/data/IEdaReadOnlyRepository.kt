package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.EdaCredential
import io.github.leogallego.ansiblejane.model.EdaCredentialType
import io.github.leogallego.ansiblejane.model.EdaDecisionEnvironment
import io.github.leogallego.ansiblejane.model.EdaEventStream
import io.github.leogallego.ansiblejane.model.EdaProject
import io.github.leogallego.ansiblejane.model.EdaRulebook
import io.github.leogallego.ansiblejane.model.EdaUser

interface IEdaReadOnlyRepository {
    suspend fun getRulebooks(
        page: Int = 1,
        pageSize: Int = 20,
        name: String? = null,
    ): Result<ListResult<EdaRulebook>>

    suspend fun getDecisionEnvironments(
        page: Int = 1,
        pageSize: Int = 20,
        name: String? = null,
    ): Result<ListResult<EdaDecisionEnvironment>>

    suspend fun getProjects(
        page: Int = 1,
        pageSize: Int = 20,
        name: String? = null,
    ): Result<ListResult<EdaProject>>

    suspend fun getCredentials(
        page: Int = 1,
        pageSize: Int = 20,
        name: String? = null,
    ): Result<ListResult<EdaCredential>>

    suspend fun getCredentialTypes(
        page: Int = 1,
        pageSize: Int = 20,
        name: String? = null,
    ): Result<ListResult<EdaCredentialType>>

    suspend fun getEventStreams(
        page: Int = 1,
        pageSize: Int = 20,
        name: String? = null,
    ): Result<ListResult<EdaEventStream>>

    suspend fun getUsers(
        page: Int = 1,
        pageSize: Int = 20,
    ): Result<ListResult<EdaUser>>
}
