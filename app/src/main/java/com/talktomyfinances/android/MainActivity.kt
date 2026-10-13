package com.talktomyfinances.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.talktomyfinances.android.ui.chat.ChatRoute
import com.talktomyfinances.android.ui.chat.ChatScreen
import com.talktomyfinances.android.ui.insights.InsightsRoute
import com.talktomyfinances.android.ui.insights.InsightsScreen
import com.talktomyfinances.android.ui.onboarding.OnboardingRoute
import com.talktomyfinances.android.ui.onboarding.OnboardingScreen
import com.talktomyfinances.android.ui.shared.BottomNavBar
import com.talktomyfinances.android.ui.theme.TalkToMyFinancesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition {
            viewModel.startDestination.value == null
        }

        enableEdgeToEdge()
        setSoftInputMode()

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val navController = rememberNavController()
            val destination = navController.currentBackStackEntryAsState().value?.destination
            val onOnboarding = destination?.hasRoute<OnboardingRoute>() == true
            val startDest by viewModel.startDestination.collectAsStateWithLifecycle()

            TalkToMyFinancesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (startDest != null) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            bottomBar = {
                                if (!onOnboarding) {
                                    BottomNavBar(
                                        selectedIndex = if (destination?.hasRoute<ChatRoute>() == true) 1 else 0
                                    ) { navigateTo(navController, route = it) }
                                }
                            }
                        ) { innerPadding ->
                            NavHost(
                                navController = navController,
                                startDestination = startDest!!,
                                modifier = Modifier.padding(innerPadding)
                            ) {
                                composable<OnboardingRoute> {
                                    OnboardingScreen(
                                        onFinished = {
                                            navController.navigate(InsightsRoute) {
                                                popUpTo(OnboardingRoute) { inclusive = true }
                                            }
                                        }
                                    )
                                }
                                composable<InsightsRoute> {
                                    InsightsScreen(
                                        openOnboarding = {
                                            navController.navigate(OnboardingRoute) {
                                                popUpTo(InsightsRoute) { inclusive = true }
                                            }
                                        },
                                        openChat = { prompt ->
                                            navController.navigate(ChatRoute(prompt)) { launchSingleTop = true }
                                        }
                                    )
                                }
                                composable<ChatRoute> { entry ->
                                    ChatScreen(prompt = entry.toRoute<ChatRoute>().prompt)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun navigateTo(navController: NavController, route: Any) {
        val startDestId = navController.graph.findStartDestination().id
        if (route == InsightsRoute) {
            navController.popBackStack(startDestId, false)
        } else {
            navController.navigate(route) {
                popUpTo(startDestId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    private fun setSoftInputMode() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }
}
