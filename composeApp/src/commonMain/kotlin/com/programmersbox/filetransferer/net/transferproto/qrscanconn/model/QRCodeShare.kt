package com.programmersbox.filetransferer.net.transferproto.qrscanconn.model

import kotlinx.serialization.Serializable

@Serializable
data class QRCodeShare(
    val version: Int,
    val deviceName: String,
    val address: Int
)