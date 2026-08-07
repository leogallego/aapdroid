package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.data.EdaActivationListResult
import io.github.leogallego.ansiblejane.data.IEdaActivationRepository
import io.github.leogallego.ansiblejane.model.EdaActivation

class FakeEdaActivationRepository : IEdaActivationRepository {
    var activations = listOf<EdaActivation>()
    var totalCount: Int? = null
    var shouldFail = false
    var failureException: Exception = RuntimeException("Test error")

    override suspend fun getActivations(page: Int, pageSize: Int): Result<EdaActivationListResult> {
        if (shouldFail) return Result.failure(failureException)
        return Result.success(
            EdaActivationListResult(
                activations = activations,
                hasMore = false,
                totalCount = totalCount ?: activations.size
            )
        )
    }

    override suspend fun getActivation(id: Int): Result<EdaActivation> {
        if (shouldFail) return Result.failure(failureException)
        return activations.find { it.id == id }?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Activation $id not found"))
    }
}
