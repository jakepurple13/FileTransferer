package com.programmersbox.filetransferer.presentation

import kotlinx.serialization.Serializable

@Serializable
data object Home

@Serializable
data object ScanQrCodeScreen

@Serializable
data class ConnectionScreen(
    val remoteAddress: Int,
    val isServer: Boolean,
    val localAddress: Int,
)