package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.AapToken
import io.github.leogallego.ansiblejane.model.Application
import io.github.leogallego.ansiblejane.model.CredentialType
import io.github.leogallego.ansiblejane.model.Group
import io.github.leogallego.ansiblejane.model.InventorySource
import io.github.leogallego.ansiblejane.model.Label
import io.github.leogallego.ansiblejane.model.NotificationTemplate
import io.github.leogallego.ansiblejane.model.Organization
import io.github.leogallego.ansiblejane.model.Role
import io.github.leogallego.ansiblejane.model.RoleDefinition
import io.github.leogallego.ansiblejane.model.SurveySpec
import io.github.leogallego.ansiblejane.model.Team
import io.github.leogallego.ansiblejane.model.User
import io.github.leogallego.ansiblejane.model.WorkflowJobTemplateNode
import kotlinx.serialization.json.JsonElement

interface IControllerReadOnlyRepository {
    suspend fun getOrganizations(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<Organization>>

    suspend fun getUsers(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<User>>

    suspend fun getTeams(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<Team>>

    suspend fun getRoles(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<Role>>

    suspend fun getRoleDefinitions(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<RoleDefinition>>

    suspend fun getGroups(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<Group>>

    suspend fun getInventorySources(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<InventorySource>>

    suspend fun getLabels(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<Label>>

    suspend fun getCredentialTypes(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<CredentialType>>

    suspend fun getNotificationTemplates(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<NotificationTemplate>>

    suspend fun getApplications(
        page: Int = 1,
        pageSize: Int = 25,
        search: String? = null,
    ): Result<ListResult<Application>>

    suspend fun getTokens(
        page: Int = 1,
        pageSize: Int = 25,
    ): Result<ListResult<AapToken>>

    suspend fun getSettings(): Result<JsonElement>

    suspend fun getConfig(): Result<JsonElement>

    suspend fun getWorkflowJobTemplateNodes(
        page: Int = 1,
        pageSize: Int = 25,
        workflowJobTemplate: Int? = null,
    ): Result<ListResult<WorkflowJobTemplateNode>>

    suspend fun getSurveySpec(id: Int): Result<SurveySpec>
}
