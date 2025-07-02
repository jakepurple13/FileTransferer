package com.programmersbox.filetransferer.net.transferproto.qrscanconn

import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice

interface QRCodeScanServerObserver : QRCodeScanObserver {

    fun requestTransferFile(remoteDevice: RemoteDevice)
}