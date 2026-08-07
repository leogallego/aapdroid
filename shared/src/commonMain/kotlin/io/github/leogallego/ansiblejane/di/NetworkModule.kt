package io.github.leogallego.ansiblejane.di

import io.github.leogallego.ansiblejane.network.ApiVersionDetector
import io.github.leogallego.ansiblejane.network.HttpClientFactory
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import io.github.leogallego.ansiblejane.network.InstanceDiscovery
import io.github.leogallego.ansiblejane.network.networkJson
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.json.Json
import org.koin.dsl.bind
import org.koin.dsl.module

val sharedNetworkModule = module {
    single<Json> { networkJson }

    single {
        HttpClientFactory(
            tokenManager = get(),
            json = get()
        )
    } bind IAapApiProvider::class

    factory { ApiVersionDetector() }

    single { InstanceDiscovery(get()) }
}
