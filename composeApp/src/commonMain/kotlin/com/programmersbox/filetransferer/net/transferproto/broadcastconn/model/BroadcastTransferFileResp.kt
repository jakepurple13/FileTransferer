package com.programmersbox.filetransferer.net.transferproto.broadcastconn.model

import kotlinx.serialization.Serializable

@Serializable
data class BroadcastTransferFileResp(
    val deviceName: String
)