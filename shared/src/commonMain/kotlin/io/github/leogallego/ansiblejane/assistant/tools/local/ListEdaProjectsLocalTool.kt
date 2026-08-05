package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.IEdaReadOnlyRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListEdaProjectsLocalTool(
    private val repository: IEdaReadOnlyRepository
) : AapLocalTool<ListEdaProjectsLocalTool.Args>(
    typeToken<Args>(),
    Args.serializer(),
    name = "list_eda_projects",
    description = "List EDA projects (Git repositories containing rulebooks)"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Filter by project name")
        val name: String? = null,
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 20, max 20)")
        @SerialName("page_size")
        val pageSize: Int = 20,
    )

    override suspend fun execute(args: Args): String {
        val pageSize = args.pageSize.coerceIn(1, 20)
        val result = repository.getProjects(
            page = args.page.coerceAtLeast(1),
            pageSize = pageSize,
            name = args.name
        ).getOrThrow()
        return listToolJson("projects", result.totalCount, result.items)
    }
}
