package io.github.leogallego.ansiblejane.network

import io.github.leogallego.ansiblejane.model.ApiVersion
import io.github.leogallego.ansiblejane.model.InstanceInfo

interface IInstanceDiscovery {
    suspend fun discover(
        baseUrl: String,
        token: String,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean = false
    ): InstanceInfo
}
