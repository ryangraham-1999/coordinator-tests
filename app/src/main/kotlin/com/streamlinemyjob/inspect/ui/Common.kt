@file:OptIn(ExperimentalMaterial3Api::class)

package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A screen: a top bar with [title] and [actions], then [content] in a column. */
@Composable
fun ScreenFrame(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, actions = actions) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), content = content)
    }
}

/** The `error` area of a screen (4.16): the owner's text for the last refused write, if any. */
@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("error").padding(vertical = 8.dp),
        )
    }
}

/** Runs a repository call off the main thread (the repository's calls block). */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
