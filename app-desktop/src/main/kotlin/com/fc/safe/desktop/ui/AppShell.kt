package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.Scaffold
import androidx.compose.material.ScaffoldState
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Top-bar + content slot + snackbar host. Every screen wraps its content in
 * an [AppShell] so title, navigation affordances, and user feedback go
 * through one place — replaces Safe Android's `ToolbarUtils.setupToolbar(...)`
 * plus the ad-hoc Toast calls scattered through Activities.
 */
@Composable
fun AppShell(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scaffoldState: ScaffoldState = rememberScaffoldState(),
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        scaffoldState = scaffoldState,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = navigationIcon,
                actions = actions,
                elevation = 2.dp,
            )
        },
        snackbarHost = { SnackbarHost(scaffoldState.snackbarHostState, Modifier) },
        content = content,
    )
}

/**
 * Exposes the Scaffold's snackbar host state to callers that need to post
 * messages from event handlers (e.g. after a long-running unlock).
 */
@Composable
fun rememberAppSnackbar(): SnackbarHostState = rememberScaffoldState().snackbarHostState
