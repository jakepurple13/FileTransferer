package com.programmersbox.filetransferer.presentation.connection

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.programmersbox.filetransferer.LocalNavController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel = viewModel { ConnectionViewModel(createSavedStateHandle()) }
) {
    val navController = LocalNavController.current

    LaunchedEffect(viewModel.connectionStatus) {
        if(viewModel.connectionStatus is ConnectionStatus.Closed) {
            delay(2500)
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() }
                    ) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding)
        ) {
            Crossfade(viewModel.connectionStatus) { target ->
                when(target) {
                    ConnectionStatus.Closed -> Text("Connection closed")
                    is ConnectionStatus.Connected -> Text("Connected")
                    ConnectionStatus.Connecting -> CircularProgressIndicator()
                }
            }
            Text("Remote Address: ${viewModel.d.remoteAddress}")
            Text("Is Server: ${viewModel.d.isServer}")
            Text("Local Address: ${viewModel.d.localAddress}")

            Text(viewModel.fileSendStatus.toString())

            TextButton(
                onClick = { viewModel.sendMsg() }
            ) { Text("Send Message") }

            TextButton(
                onClick = { viewModel.sendFileExample() }
            ) { Text("Send File") }

            TextButton(
                onClick = { viewModel.downloadFileExample() }
            ) { Text("Download File") }
        }
    }
}