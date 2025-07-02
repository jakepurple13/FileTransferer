package com.programmersbox.filetransferer.net.transferproto.broadcastconn

import java.net.InetAddress

sealed class BroadcastSenderState {
    data object NoConnection : BroadcastSenderState()
    data object Requesting : BroadcastSenderState()
    data class Active(
        val broadcastAddress: InetAddress
    ) : BroadcastSenderState()
}
