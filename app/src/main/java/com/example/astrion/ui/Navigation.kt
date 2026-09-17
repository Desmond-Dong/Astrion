package com.example.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.astrion.ui.screens.panel.DeviceDetailScreen
import com.example.astrion.ui.screens.panel.PanelHomeScreen
import com.example.astrion.ui.theme.RemoteBackground
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

@Serializable
object Home

/** Device detail page, addressed by the layout card id. */
@Serializable
data class DeviceDetail(val cardId: String)

@Composable
fun PanelNavHost(
    openCard: SharedFlow<String>,
    navigateBack: SharedFlow<Unit>,
    goHome: SharedFlow<Unit>,
) {
    val navController = rememberNavController()

    // Key bindings can open card pages from anywhere (§3.10.5 快捷键);
    // the physical BACK/HOME keys navigate the app itself.
    LaunchedEffect(navController) {
        openCard.collect { cardId ->
            navController.navigate(DeviceDetail(cardId))
        }
    }
    LaunchedEffect(navController) {
        navigateBack.collect { navController.popBackStack() }
    }
    LaunchedEffect(navController) {
        goHome.collect {
            navController.popBackStack(Home, inclusive = false)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = Modifier
            .fillMaxSize()
            .background(RemoteBackground)
    ) {
        composable<Home> {
            PanelHomeScreen(navController)
        }
        composable<DeviceDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<DeviceDetail>()
            DeviceDetailScreen(
                navController = navController,
                cardId = route.cardId
            )
        }
    }
}
