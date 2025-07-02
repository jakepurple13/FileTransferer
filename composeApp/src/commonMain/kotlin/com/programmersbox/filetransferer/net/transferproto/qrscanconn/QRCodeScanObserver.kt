package com.programmersbox.filetransferer.net.transferproto.qrscanconn

interface QRCodeScanObserver {

    fun onNewState(state: QRCodeScanState)
}