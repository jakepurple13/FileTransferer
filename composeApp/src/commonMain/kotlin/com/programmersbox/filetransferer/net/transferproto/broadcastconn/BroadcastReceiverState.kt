package com.programmersbox.filetransferer.net.transferproto.broadcastconn


sealed class BroadcastReceiverState {
    data object NoConnection : BroadcastReceiverState()

    data object Requesting : BroadcastReceiverState()

    data object Active : BroadcastReceiverState()
}