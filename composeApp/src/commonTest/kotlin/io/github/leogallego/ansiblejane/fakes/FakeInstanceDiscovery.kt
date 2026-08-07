package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.model.ApiVersion
import io.github.leogallego.ansiblejane.model.InstanceInfo
import io.github.leogallego.ansiblejane.network.IInstanceDiscovery

class FakeInstanceDiscovery : IInstanceDiscovery {
    var nextInfo: InstanceInfo = InstanceInfo(
        controllerVersion = "4.6.0",
        platformType = "AAP",
        components = listOf("CONTROLLER")
    )
    var shouldFail = false
    var failureException: Exception = RuntimeException("Discovery failed")
    var discoverCalls = 0

    override suspend fun discover(
        baseUrl: String,
        token: String,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean
    ): InstanceInfo {
        discoverCalls++
        if (shouldFail) throw failureException
        return nextInfo
    }
}
