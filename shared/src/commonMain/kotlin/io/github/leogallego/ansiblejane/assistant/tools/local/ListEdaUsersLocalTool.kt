package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.IEdaReadOnlyRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListEdaUsersLocalTool(
    private val repository: IEdaReadOnlyRepository
) : AapLocalTool<ListEdaUsersLocalTool.Args>(
    typeToken<Args>(),
    Args.serializer(),
    name = "list_eda_users",
    description = "List EDA users (requires EDA to be installed on the AAP instance)"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 20, max 20)")
        @SerialName("page_size")
        val pageSize: Int = 20,
    )

    override suspend fun execute(args: Args): String {
        val pageSize = args.pageSize.coerceIn(1, 20)
        val result = repository.getUsers(
            page = args.page.coerceAtLeast(1),
            pageSize = pageSize
        ).getOrThrow()
        return listToolJson("users", result.totalCount, result.items)
    }
}
