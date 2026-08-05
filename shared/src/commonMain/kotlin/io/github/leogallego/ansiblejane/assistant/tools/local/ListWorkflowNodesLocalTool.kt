package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.IControllerReadOnlyRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListWorkflowNodesLocalTool(
    private val repository: IControllerReadOnlyRepository
) : AapLocalTool<ListWorkflowNodesLocalTool.Args>(
    typeToken<Args>(), Args.serializer(),
    name = "list_workflow_nodes",
    description = "List workflow job template nodes, optionally filtered by workflow template ID"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Filter by workflow job template ID")
        @SerialName("workflow_job_template")
        val workflowJobTemplate: Int? = null,
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 25, max 25)")
        @SerialName("page_size")
        val pageSize: Int = 25
    )

    override suspend fun execute(args: Args): String {
        val pageSize = args.pageSize.coerceIn(1, 25)
        val result = repository.getWorkflowJobTemplateNodes(
            page = args.page.coerceAtLeast(1),
            pageSize = pageSize,
            workflowJobTemplate = args.workflowJobTemplate
        ).getOrThrow()
        return listToolJson("workflow_nodes", result.totalCount, result.items)
    }
}
