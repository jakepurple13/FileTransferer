package com.programmersbox.filetransferer.net.transferproto.broadcastconn

import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice

interface BroadcastSenderObserver {

    fun onNewState(state: BroadcastSenderState)

    fun requestTransferFile(remoteDevice: RemoteDevice)
}