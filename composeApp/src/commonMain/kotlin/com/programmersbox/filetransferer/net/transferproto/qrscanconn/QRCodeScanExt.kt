package com.programmersbox.filetransferer.net.transferproto.qrscanconn

import com.programmersbox.filetransferer.net.resumeExceptionIfActive
import com.programmersbox.filetransferer.net.resumeIfActive
import com.programmersbox.filetransferer.net.transferproto.SimpleCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress

suspend fun QRCodeScanServer.startQRCodeScanServerSuspend(localAddress: InetAddress): Unit =
    suspendCancellableCoroutine { cont ->
        startQRCodeScanServer(
            localAddress = localAddress,
            callback = object : SimpleCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    cont.resumeIfActive(Unit)
                }

                override fun onError(errorMsg: String) {
                    cont.resumeExceptionIfActive(Throwable(errorMsg))
                }
            }
        )
    }


suspend fun QRCodeScanClient.startQRCodeScanClientSuspend(serverAddress: InetAddress): Unit =
    suspendCancellableCoroutine { cont ->
        startQRCodeScanClient(
            serverAddress = serverAddress,
            callback = object : SimpleCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    cont.resumeIfActive(Unit)
                }

                override fun onError(errorMsg: String) {
                    cont.resumeExceptionIfActive(Throwable(errorMsg))
                }
            }
        )
    }

suspend fun QRCodeScanClient.requestFileTransferSuspend(targetAddress: InetAddress, deviceName: String): Unit =
    suspendCancellableCoroutine { cont ->
        requestFileTransfer(
            targetAddress = targetAddress,
            deviceName = deviceName,
            simpleCallback = object : SimpleCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    cont.resumeIfActive(Unit)
                }

                override fun onError(errorMsg: String) {
                    cont.resumeExceptionIfActive(Throwable(errorMsg))
                }
            }
        )
    }