package com.programmersbox.filetransferer.presentation.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel = viewModel { ConnectionViewModel(createSavedStateHandle()) }
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding)
        ) {
            Text("Remote Address: ${viewModel.d.remoteAddress}")
            Text("Is Server: ${viewModel.d.isServer}")
            Text("Local Address: ${viewModel.d.localAddress}")
            TextButton(
                onClick = { viewModel.sendMsg() }
            ) { Text("Send Message") }
        }
    }
}