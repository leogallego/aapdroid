package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.di.sharedNetworkModule
import io.github.leogallego.ansiblejane.notification.ApprovalNotificationManager
import io.github.leogallego.ansiblejane.notification.IApprovalNotificationManager
import io.github.leogallego.ansiblejane.platform.BackgroundWorker
import io.github.leogallego.ansiblejane.platform.ConnectivityObserver
import io.github.leogallego.ansiblejane.platform.DataStoreFactory
import io.github.leogallego.ansiblejane.platform.DeviceResources
import io.github.leogallego.ansiblejane.platform.NotificationManager
import io.github.leogallego.ansiblejane.platform.PlatformUtils
import io.github.leogallego.ansiblejane.platform.SecureKeyStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val platformModule = module {
    single { DataStoreFactory(androidContext()) }
    single { SecureKeyStorage(androidContext()) }
    single { ConnectivityObserver(androidContext()) }
    single { BackgroundWorker(androidContext()) }
    single { NotificationManager(androidContext()) }
    single { PlatformUtils(androidContext()) }
    single { DeviceResources(androidContext()) }
    single<IApprovalNotificationManager> { ApprovalNotificationManager() }
}

val dataModule = module {
    includes(sharedDataModule, sharedRepositoryModule, sharedNetworkModule, platformModule)
}
