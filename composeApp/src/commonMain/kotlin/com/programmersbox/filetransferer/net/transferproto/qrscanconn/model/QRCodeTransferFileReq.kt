package com.programmersbox.filetransferer.net.transferproto.qrscanconn.model

import kotlinx.serialization.Serializable

@Serializable
data class QRCodeTransferFileReq(
    val version: Int,
    val deviceName: String
)