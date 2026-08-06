package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.data.IControllerReadOnlyRepository
import io.github.leogallego.ansiblejane.network.networkJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class GetSurveySpecLocalTool(
    private val repository: IControllerReadOnlyRepository
) : AapLocalTool<GetSurveySpecLocalTool.Args>(
    typeToken<Args>(), Args.serializer(),
    "get_survey_spec", "Get the survey specification for a job template (required variables and prompts)"
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Job template ID")
        val id: Int
    )

    override suspend fun execute(args: Args): String {
        val survey = repository.getSurveySpec(args.id).getOrThrow()
        return networkJson.encodeToString(survey)
    }
}
