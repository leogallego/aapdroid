package io.github.leogallego.ansiblejane.assistant.tools.local

import ai.koog.serialization.typeToken
import io.github.leogallego.ansiblejane.assistant.tools.AapLocalTool
import io.github.leogallego.ansiblejane.assistant.tools.EmptyArgs
import io.github.leogallego.ansiblejane.data.IControllerReadOnlyRepository

class GetConfigLocalTool(
    private val repository: IControllerReadOnlyRepository
) : AapLocalTool<EmptyArgs>(
    typeToken<EmptyArgs>(), EmptyArgs.serializer(),
    "get_config", "Get AAP configuration including license info, version, and platform details"
) {
    override suspend fun execute(args: EmptyArgs): String {
        val config = repository.getConfig().getOrThrow()
        return config.toString()
    }
}
