package com.programmersbox.filetransferer.net.transferproto.p2pconn.model

import kotlinx.serialization.Serializable

@Serializable
data class P2pHandshakeReq(
    val version: Int,
    val deviceName: String
)