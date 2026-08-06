package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.IControllerReadOnlyRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListGroupsLocalTool(
    private val repository: IControllerReadOnlyRepository
) : AapLocalTool<ListGroupsLocalTool.Args>(
    typeToken<Args>(), Args.serializer(),
    name = "list_groups",
    description = "List inventory groups with optional search"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Search term to filter by group name")
        val search: String? = null,
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 25, max 25)")
        @SerialName("page_size")
        val pageSize: Int = 25
    )

    override suspend fun execute(args: Args): String {
        val pageSize = args.pageSize.coerceIn(1, 25)
        val result = repository.getGroups(
            page = args.page.coerceAtLeast(1),
            pageSize = pageSize,
            search = args.search
        ).getOrThrow()
        return listToolJson("groups", result.totalCount, result.items)
    }
}
