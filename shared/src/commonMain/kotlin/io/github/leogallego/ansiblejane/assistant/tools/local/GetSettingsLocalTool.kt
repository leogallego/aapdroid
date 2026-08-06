package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.EmptyArgs
import io.github.leogallego.ansiblejane.data.IControllerReadOnlyRepository

class GetSettingsLocalTool(
    private val repository: IControllerReadOnlyRepository
) : AapLocalTool<EmptyArgs>(
    typeToken<EmptyArgs>(), EmptyArgs.serializer(),
    "get_settings", "Get AAP system settings categories and their endpoints"
) {
    override suspend fun execute(args: EmptyArgs): String {
        val settings = repository.getSettings().getOrThrow()
        return settings.toString()
    }
}
