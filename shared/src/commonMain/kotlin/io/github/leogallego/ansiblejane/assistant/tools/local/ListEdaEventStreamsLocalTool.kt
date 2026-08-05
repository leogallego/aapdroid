package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.IEdaReadOnlyRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListEdaEventStreamsLocalTool(
    private val repository: IEdaReadOnlyRepository
) : AapLocalTool<ListEdaEventStreamsLocalTool.Args>(
    typeToken<Args>(),
    Args.serializer(),
    name = "list_eda_event_streams",
    description = "List EDA event streams (webhook endpoints for external events)"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Filter by name")
        val name: String? = null,
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 20, max 20)")
        @SerialName("page_size")
        val pageSize: Int = 20,
    )

    override suspend fun execute(args: Args): String {
        val pageSize = args.pageSize.coerceIn(1, 20)
        val result = repository.getEventStreams(
            page = args.page.coerceAtLeast(1),
            pageSize = pageSize,
            name = args.name
        ).getOrThrow()
        return listToolJson("event_streams", result.totalCount, result.items)
    }
}
