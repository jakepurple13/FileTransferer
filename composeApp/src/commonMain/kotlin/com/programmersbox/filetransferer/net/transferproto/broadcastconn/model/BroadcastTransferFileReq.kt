package com.programmersbox.filetransferer.net.transferproto.broadcastconn.model

import kotlinx.serialization.Serializable

@Serializable
data class BroadcastTransferFileReq(
    val version: Int,
    val deviceName: String
)