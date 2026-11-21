package com.rainyworks.flow.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainyworks.flow.feature.home.ui.HomeContent

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpenProject)

    LaunchedEffect(viewModel) {
        viewModel.openProject.collect { open(it) }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshCacheSize()
        onPauseOrDispose { }
    }

    HomeContent(state = state, onAction = viewModel::onAction, modifier = modifier)
}
