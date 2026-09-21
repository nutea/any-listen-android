package io.github.nutea.anylisten.ui

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clipToBounds
import androidx.navigation.compose.composable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

private val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Paired opaque pages move edge-to-edge, without fading text through each other. */
@Composable
fun LibraryNavHost(nav: NavHostController, modifier: Modifier = Modifier,
    motion: Boolean = rememberMotionEnabled(), builder: NavGraphBuilder.() -> Unit) {
    fun detail(route: String?) = route == "search" || route?.startsWith("playlist/") == true || isCatalogRoute(route)
    NavHost(navController = nav, startDestination = "library", modifier = modifier.clipToBounds(),
        enterTransition = {
            if (motion && detail(targetState.destination.route) && initialState.destination.route != "player")
                slideInHorizontally(tween(320, easing = PageEasing)) { it } else EnterTransition.None
        },
        exitTransition = {
            if (motion && detail(targetState.destination.route) && initialState.destination.route != "player")
                slideOutHorizontally(tween(320, easing = PageEasing)) { -it } else ExitTransition.None
        },
        popEnterTransition = {
            if (motion && detail(initialState.destination.route))
                slideInHorizontally(tween(320, easing = PageEasing)) { -it } else EnterTransition.None
        },
        popExitTransition = {
            if (motion && detail(initialState.destination.route))
                slideOutHorizontally(tween(320, easing = PageEasing)) { it } else ExitTransition.None
        }, builder = builder)
}

fun NavGraphBuilder.libraryPage(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
    composable(route) { entry ->
        Surface(Modifier.fillMaxSize().graphicsLayer()) { content(entry) }
    }
}

fun NavHostController.acceptsInput(entry: NavBackStackEntry): Boolean =
    currentBackStackEntry?.id == entry.id && entry.lifecycle.currentState == Lifecycle.State.RESUMED

/** Library always means its overview, never a restored child playlist. */
fun NavHostController.selectMainTab(target: String) {
    if (currentDestination?.route == target) return
    navigate(target) {
        popUpTo(graph.findStartDestination().id) { saveState = target != "library" }
        launchSingleTop = true
        restoreState = target != "library"
    }
}
