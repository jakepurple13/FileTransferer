package com.programmersbox.filetransferer.net.transferproto.p2pconn

import java.net.InetSocketAddress

sealed class P2pConnectionState {

    data object NoConnection : P2pConnectionState()

    data object Requesting : P2pConnectionState()

    class Active(
        val localAddress: InetSocketAddress?,
        val remoteAddress: InetSocketAddress?
    ) : P2pConnectionState()

    data class Handshake(
        val localAddress: InetSocketAddress,
        val remoteAddress: InetSocketAddress,
        val remoteDeviceName: String
    ) : P2pConnectionState()
}