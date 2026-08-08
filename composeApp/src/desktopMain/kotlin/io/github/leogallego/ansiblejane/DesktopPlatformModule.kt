package io.github.leogallego.ansiblejane

import io.github.leogallego.ansiblejane.platform.BackgroundWorker
import io.github.leogallego.ansiblejane.platform.ConnectivityObserver
import io.github.leogallego.ansiblejane.platform.DataStoreFactory
import io.github.leogallego.ansiblejane.platform.DeviceResources
import io.github.leogallego.ansiblejane.platform.NotificationManager
import io.github.leogallego.ansiblejane.platform.PlatformUtils
import io.github.leogallego.ansiblejane.platform.SecureKeyStorage
import org.koin.dsl.module

val desktopPlatformModule = module {
    single { DataStoreFactory() }
    single { SecureKeyStorage() }
    single { ConnectivityObserver() }
    single { BackgroundWorker() }
    single { NotificationManager() }
    single { PlatformUtils() }
    single { DeviceResources() }
}
