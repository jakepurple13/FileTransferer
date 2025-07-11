package com.programmersbox.filetransferer.net.transferproto.p2pconn

interface P2pConnectionObserver {

    fun onNewState(state: P2pConnectionState)

    fun requestTransferFile(handshake: P2pConnectionState.Handshake, isReceiver: Boolean)
}