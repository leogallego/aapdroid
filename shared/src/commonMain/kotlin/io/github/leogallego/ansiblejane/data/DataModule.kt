package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.assistant.data.AssistantRepository
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.data.backup.BackupManager
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import io.github.leogallego.ansiblejane.notification.ApprovalTracker
import io.github.leogallego.ansiblejane.platform.DataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.bind
import org.koin.dsl.module

val sharedDataModule = module {
    single { ToolManifestRepository(get<DataStoreFactory>().createPreferencesDataStore("credentials")) } bind IToolManifestRepository::class
    single { TokenManager(get(), get(), get()) } bind ITokenManager::class
    single { UserPreferencesRepository(get()) } bind IUserPreferencesRepository::class
    single { AssistantRepository(get(), get()) } bind IAssistantRepository::class
    single { ApprovalTracker(get()) }
    single { BackupManager() }
}

val sharedRepositoryModule = module {
    single { AuthRepository(get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) } bind IAuthRepository::class
    single { TemplateRepository(get<IAapApiProvider>()) } bind ITemplateRepository::class
    single { JobRepository(get<IAapApiProvider>()) } bind IJobRepository::class
    single { WorkflowRepository(get<IAapApiProvider>()) } bind IWorkflowRepository::class
    single { ScheduleRepository(get<IAapApiProvider>()) } bind IScheduleRepository::class
    single { EdaAuditRepository(get<IAapApiProvider>()) } bind IEdaAuditRepository::class
    single { InventoryRepository(get<IAapApiProvider>()) } bind IInventoryRepository::class
    single { HostRepository(get<IAapApiProvider>()) } bind IHostRepository::class
    single { InfrastructureRepository(get<IAapApiProvider>()) } bind IInfrastructureRepository::class
    single { CredentialRepository(get<IAapApiProvider>()) } bind ICredentialRepository::class
    single { ProjectRepository(get<IAapApiProvider>()) } bind IProjectRepository::class
    single { EdaActivationRepository(get<IAapApiProvider>()) } bind IEdaActivationRepository::class
    single { ControllerReadOnlyRepository(get<IAapApiProvider>()) } bind IControllerReadOnlyRepository::class
    single { EdaReadOnlyRepository(get<IAapApiProvider>()) } bind IEdaReadOnlyRepository::class
    single { PlatformRepository(get<IAapApiProvider>()) } bind IPlatformRepository::class
    single { HubRepository(get<IAapApiProvider>()) } bind IHubRepository::class
}
