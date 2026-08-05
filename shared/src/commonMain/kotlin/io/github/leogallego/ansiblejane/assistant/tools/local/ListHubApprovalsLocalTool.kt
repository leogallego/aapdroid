package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.listToolJson
import io.github.leogallego.ansiblejane.data.IHubRepository
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.model.AapComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ListHubApprovalsLocalTool(
    private val repository: IHubRepository,
    private val tokenManager: ITokenManager
) : AapLocalTool<ListHubApprovalsLocalTool.Args>(
    typeToken<Args>(), Args.serializer(),
    name = "list_hub_approvals",
    description = "List collection versions pending approval in Private Automation Hub"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Filter by repository label (e.g. 'staging' for pending approval)")
        val status: String? = null,
        @property:LLMDescription("Page number (default 1)")
        val page: Int = 1,
        @property:LLMDescription("Results per page (default 20, max 20)")
        @SerialName("page_size")
        val pageSize: Int = 20
    )

    override suspend fun execute(args: Args): String {
        val info = tokenManager.activeInstance.value?.instanceInfo
        if (info != null && !info.hasComponent(AapComponent.HUB)) {
            return """{"error": "Hub is not available on this instance"}"""
        }
        val result = repository.getCollectionVersions(
            page = args.page.coerceAtLeast(1),
            pageSize = args.pageSize.coerceIn(1, 20),
            status = args.status
        ).getOrThrow()
        return listToolJson("collection_versions", result.totalCount, result.items)
    }
}
