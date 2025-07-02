package com.programmersbox.filetransferer.net.transferproto.broadcastconn

import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice

interface BroadcastReceiverObserver {

    fun onNewState(state: BroadcastReceiverState)

    fun onNewBroadcast(remoteDevice: RemoteDevice)

    fun onActiveRemoteDevicesUpdate(remoteDevices: List<RemoteDevice>)
}