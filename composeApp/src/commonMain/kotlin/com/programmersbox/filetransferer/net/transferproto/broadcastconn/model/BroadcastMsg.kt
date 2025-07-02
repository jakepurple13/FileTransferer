package com.programmersbox.filetransferer.net.transferproto.broadcastconn.model

import kotlinx.serialization.Serializable

@Serializable
data class BroadcastMsg(
    val version: Int,
    val deviceName: String
)
