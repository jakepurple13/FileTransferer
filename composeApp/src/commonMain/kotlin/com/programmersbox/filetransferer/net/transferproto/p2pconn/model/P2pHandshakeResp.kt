package com.programmersbox.filetransferer.net.transferproto.p2pconn.model

import kotlinx.serialization.Serializable

@Serializable
data class P2pHandshakeResp(
    val deviceName: String
)