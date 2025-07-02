package com.programmersbox.filetransferer.net.transferproto.broadcastconn

import com.programmersbox.filetransferer.net.resumeExceptionIfActive
import com.programmersbox.filetransferer.net.resumeIfActive
import com.programmersbox.filetransferer.net.transferproto.SimpleCallback
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress

suspend fun BroadcastSender.startSenderSuspend(localAddress: InetAddress, broadcastAddress: InetAddress,) = suspendCancellableCoroutine<Unit> { cont ->
    startBroadcastSender(localAddress, broadcastAddress, object : SimpleCallback<Unit> {

        override fun onError(errorMsg: String) {
            cont.resumeExceptionIfActive(Throwable(errorMsg))
        }

        override fun onSuccess(data: Unit) {
            cont.resumeIfActive(data)
        }
    })
}

suspend fun BroadcastSender.waitClose() = suspendCancellableCoroutine<Unit> { cont ->
    addObserver(object : BroadcastSenderObserver {
        init {
            cont.invokeOnCancellation {
                removeObserver(this)
            }
        }

        override fun onNewState(state: BroadcastSenderState) {
            if (state is BroadcastSenderState.NoConnection) {
                cont.resumeIfActive(Unit)
            }
        }

        override fun requestTransferFile(remoteDevice: RemoteDevice) {}
    })
}