package io.github.leogallego.ansiblejane.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.data.IAuthRepository
import io.github.leogallego.ansiblejane.ui.auth.AuthScreen
import io.github.leogallego.ansiblejane.ui.jobs.JobStatusScreen
import io.github.leogallego.ansiblejane.ui.main.MainScreen
import io.github.leogallego.ansiblejane.ui.approval.ApprovalDetailScreen
import io.github.leogallego.ansiblejane.ui.workflows.WorkflowJobStatusScreen
import io.github.leogallego.ansiblejane.ui.workflows.WorkflowTemplateDetailScreen
import org.koin.compose.koinInject

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    assistantContent: @Composable () -> Unit = {},
    settingsContent: @Composable (onLogout: () -> Unit, onNavigateBack: () -> Unit, onAddInstance: () -> Unit, initialTab: String?) -> Unit = { _, _, _, _ -> },
    onHandleDeepLink: ((NavHostController) -> Unit)? = null
) {
    val tokenManager: ITokenManager = koinInject()
    val authRepository: IAuthRepository = koinInject()

    val instances by tokenManager.instances.collectAsState()
    LaunchedEffect(instances) {
        if (instances.isEmpty()) {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute != null
                && !currentRoute.contains("AuthRoute")
                && !currentRoute.contains("SplashRoute")
            ) {
                navController.navigate(AuthRoute()) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        authRepository.unauthorizedEvent.collect { instanceId ->
            val currentRoute = navController.currentDestination?.route
            if (currentRoute != null && currentRoute.contains("AuthRoute")) {
                return@collect
            }

            if (instanceId.isNotBlank()) {
                val instance = tokenManager.getInstanceById(instanceId)
                if (instance != null) {
                    navController.navigate(
                        AuthRoute(
                            mode = "reauth",
                            instanceId = instanceId,
                            url = instance.baseUrl,
                            alias = instance.alias ?: "",
                            trustSelfSigned = instance.trustSelfSigned
                        )
                    )
                    return@collect
                }
            }
            navController.navigate(AuthRoute()) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    onHandleDeepLink?.invoke(navController)

    NavHost(
        navController = navController,
        startDestination = SplashRoute,
        modifier = modifier
    ) {
        composable<SplashRoute>(
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            LaunchedEffect(Unit) {
                val hasInstances = tokenManager.loadCredentials()
                val destination = if (hasInstances) MainRoute else AuthRoute()
                navController.navigate(destination) {
                    popUpTo<SplashRoute> { inclusive = true }
                }
            }
        }

        composable<AuthRoute>(
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<AuthRoute>()
            val isAddMode = route.mode == "add"
            val reAuthInstanceId = if (route.mode == "reauth" && route.instanceId.isNotBlank()) {
                route.instanceId
            } else null

            AuthScreen(
                onNavigateToDashboard = {
                    if (isAddMode || reAuthInstanceId != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(MainRoute) {
                            popUpTo<AuthRoute> { inclusive = true }
                        }
                    }
                },
                onCancel = if (isAddMode || reAuthInstanceId != null) {
                    { navController.popBackStack() }
                } else null,
                preFilledUrl = route.url.ifBlank { null },
                preFilledAlias = route.alias.ifBlank { null },
                reAuthInstanceId = reAuthInstanceId,
                isAddInstance = isAddMode,
                preFilledTrustSelfSigned = route.trustSelfSigned
            )
        }

        composable<MainRoute>(
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }
        ) {
            MainScreen(
                onNavigateToSettings = { initialTab ->
                    navController.navigate(SettingsRoute(initialTab = initialTab))
                },
                onNavigateToApproval = { approvalId ->
                    navController.navigate(ApprovalDetailRoute(approvalId))
                }
            ) { tab, segment ->
                TabContent(
                    tab = tab,
                    segment = segment,
                    assistantContent = assistantContent,
                    onNavigateToJobStatus = { jobId ->
                        navController.navigate(JobStatusRoute(jobId))
                    },
                    onNavigateToWorkflowJobStatus = { workflowJobId ->
                        navController.navigate(WorkflowJobStatusRoute(workflowJobId))
                    },
                    onNavigateToWorkflowTemplateDetail = { templateId, templateName ->
                        navController.navigate(WorkflowTemplateDetailRoute(templateId, templateName))
                    }
                )
            }
        }

        composable<JobStatusRoute>(
            enterTransition = { slideInHorizontally { it } },
            exitTransition = { slideOutHorizontally { -it } },
            popEnterTransition = { slideInHorizontally { -it } },
            popExitTransition = { slideOutHorizontally { it } }
        ) {
            JobStatusScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<WorkflowJobStatusRoute>(
            enterTransition = { slideInHorizontally { it } },
            exitTransition = { slideOutHorizontally { -it } },
            popEnterTransition = { slideInHorizontally { -it } },
            popExitTransition = { slideOutHorizontally { it } }
        ) {
            WorkflowJobStatusScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<WorkflowTemplateDetailRoute>(
            enterTransition = { slideInHorizontally { it } },
            exitTransition = { slideOutHorizontally { -it } },
            popEnterTransition = { slideInHorizontally { -it } },
            popExitTransition = { slideOutHorizontally { it } }
        ) {
            WorkflowTemplateDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToWorkflowJobStatus = { workflowJobId ->
                    navController.navigate(WorkflowJobStatusRoute(workflowJobId))
                }
            )
        }

        composable<ApprovalDetailRoute>(
            enterTransition = { slideInHorizontally { it } },
            exitTransition = { slideOutHorizontally { -it } },
            popEnterTransition = { slideInHorizontally { -it } },
            popExitTransition = { slideOutHorizontally { it } }
        ) {
            ApprovalDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute>(
            enterTransition = { slideInHorizontally { it } },
            exitTransition = { slideOutHorizontally { -it } },
            popEnterTransition = { slideInHorizontally { -it } },
            popExitTransition = { slideOutHorizontally { it } }
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<SettingsRoute>()
            settingsContent(
                {
                    navController.navigate(AuthRoute()) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                { navController.popBackStack() },
                { navController.navigate(AuthRoute(mode = "add")) },
                route.initialTab
            )
        }
    }
}
