package io.github.leogallego.ansiblejane.presentation

import io.github.leogallego.ansiblejane.presentation.approval.ApprovalDetailViewModel
import io.github.leogallego.ansiblejane.presentation.auth.AuthViewModel
import io.github.leogallego.ansiblejane.presentation.notifications.NotificationsViewModel
import io.github.leogallego.ansiblejane.presentation.dashboard.DashboardViewModel
import io.github.leogallego.ansiblejane.presentation.settings.BackupViewModel
import io.github.leogallego.ansiblejane.presentation.jobs.JobStatusViewModel
import io.github.leogallego.ansiblejane.presentation.jobs.RecentJobsViewModel
import io.github.leogallego.ansiblejane.presentation.eda.EdaAuditViewModel
import io.github.leogallego.ansiblejane.presentation.hosts.HostDetailViewModel
import io.github.leogallego.ansiblejane.presentation.hosts.HostsViewModel
import io.github.leogallego.ansiblejane.presentation.hosts.InventoryHostsViewModel
import io.github.leogallego.ansiblejane.presentation.inventory.InventoriesViewModel
import io.github.leogallego.ansiblejane.presentation.main.MainViewModel
import io.github.leogallego.ansiblejane.presentation.schedules.SchedulesViewModel
import io.github.leogallego.ansiblejane.presentation.templates.TemplatesViewModel
import io.github.leogallego.ansiblejane.presentation.workflows.WorkflowJobStatusViewModel
import io.github.leogallego.ansiblejane.presentation.workflows.WorkflowTemplateDetailViewModel
import io.github.leogallego.ansiblejane.presentation.workflows.WorkflowTemplatesViewModel
import io.github.leogallego.ansiblejane.assistant.presentation.AssistantViewModel
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.presentation.settings.SettingsViewModel
import io.github.leogallego.ansiblejane.network.networkJson
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val presentationModule = module {
    viewModelOf(::ApprovalDetailViewModel)
    viewModelOf(::AuthViewModel)
    viewModelOf(::NotificationsViewModel)
    viewModelOf(::DashboardViewModel)
    viewModelOf(::TemplatesViewModel)
    viewModelOf(::JobStatusViewModel)
    viewModelOf(::RecentJobsViewModel)
    viewModelOf(::WorkflowTemplatesViewModel)
    viewModelOf(::WorkflowJobStatusViewModel)
    viewModelOf(::WorkflowTemplateDetailViewModel)
    viewModelOf(::SchedulesViewModel)
    viewModelOf(::EdaAuditViewModel)
    viewModelOf(::InventoriesViewModel)
    viewModelOf(::InventoryHostsViewModel)
    viewModelOf(::HostsViewModel)
    viewModel { params ->
        HostDetailViewModel(
            hostId = params.get(),
            hostRepository = get(),
        )
    }
    viewModelOf(::MainViewModel)
    viewModelOf(::BackupViewModel)
    viewModel {
        AssistantViewModel(
            mcpServerManager = get(),
            repository = get(),
            tokenManager = get(),
            manifestRepository = get(),
            toolRouter = get(),
            localModelRepository = get(),
            localTools = getAll<LocalTool>()
        )
    }
    viewModel {
        SettingsViewModel(
            tokenManager = get(),
            apiProvider = get(),
            userPreferences = get(),
            assistantRepository = get(),
            mcpServerManager = get(),
            manifestRepository = get(),
            instanceDiscovery = get(),
            toolRouter = get(),
            json = networkJson
        )
    }
}
