package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.data.IPlatformRepository
import io.github.leogallego.ansiblejane.model.AapComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListPlatformUsersLocalTool(
    private val repository: IPlatformRepository,
    private val tokenManager: ITokenManager
) : AapLocalTool<ListPlatformUsersLocalTool.Args>(
    typeToken<Args>(), Args.serializer(),
    name = "list_platform_users",
    description = "List users from the AAP Gateway/Platform (authoritative source in AAP 2.5+)"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Search term to filter by username")
        val search: String? = null,
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 25, max 25)")
        @SerialName("page_size")
        val pageSize: Int = 25
    )

    override suspend fun execute(args: Args): String {
        val info = tokenManager.activeInstance.value?.instanceInfo
        if (info != null && !info.hasComponent(AapComponent.GATEWAY)) {
            return """{"error": "Gateway/Platform is not available on this instance"}"""
        }
        val result = repository.getUsers(
            page = args.page.coerceAtLeast(1),
            pageSize = args.pageSize.coerceIn(1, 25),
            search = args.search
        ).getOrThrow()
        return listToolJson("users", result.totalCount, result.items)
    }
}
