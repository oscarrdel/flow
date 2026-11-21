package com.rainyworks.flow

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rainyworks.flow.feature.editor.EditorScreen
import com.rainyworks.flow.feature.export.ExportScreen
import com.rainyworks.flow.feature.home.HomeScreen
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
object HomeRoute

@Serializable
data class EditorRoute(val projectId: String)

@Serializable
data class ExportRoute(val projectId: String)

@Composable
fun FlowNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(onCreateProject = {
                navController.navigate(EditorRoute(UUID.randomUUID().toString()))
            })
        }
        composable<EditorRoute> { entry ->
            val route = entry.toRoute<EditorRoute>()
            EditorScreen(projectId = route.projectId, onClose = { navController.popBackStack() })
        }
        composable<ExportRoute> { entry ->
            val route = entry.toRoute<ExportRoute>()
            ExportScreen(projectId = route.projectId, onDone = { navController.popBackStack() })
        }
    }
}
