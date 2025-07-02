package com.programmersbox.filetransferer.net.transferproto.broadcastconn.model

import java.net.InetSocketAddress

data class RemoteDevice(
    val remoteAddress: InetSocketAddress,
    val deviceName: String
)