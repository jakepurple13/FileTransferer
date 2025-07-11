package com.programmersbox.filetransferer.net.transferproto.broadcastconn

import com.programmersbox.filetransferer.net.ILog
import com.programmersbox.filetransferer.net.netty.INettyConnectionTask
import com.programmersbox.filetransferer.net.netty.NettyConnectionObserver
import com.programmersbox.filetransferer.net.netty.NettyTaskState
import com.programmersbox.filetransferer.net.netty.PackageData
import com.programmersbox.filetransferer.net.netty.extensions.ConnectionClientImpl
import com.programmersbox.filetransferer.net.netty.extensions.ConnectionServerImpl
import com.programmersbox.filetransferer.net.netty.extensions.IClientManager
import com.programmersbox.filetransferer.net.netty.extensions.IServer
import com.programmersbox.filetransferer.net.netty.extensions.requestSimplify
import com.programmersbox.filetransferer.net.netty.extensions.simplifyServer
import com.programmersbox.filetransferer.net.netty.extensions.withClient
import com.programmersbox.filetransferer.net.netty.extensions.withServer
import com.programmersbox.filetransferer.net.netty.udp.NettyUdpConnectionTask
import com.programmersbox.filetransferer.net.netty.udp.NettyUdpConnectionTask.Companion.ConnectionType
import com.programmersbox.filetransferer.net.transferproto.SimpleCallback
import com.programmersbox.filetransferer.net.transferproto.SimpleObservable
import com.programmersbox.filetransferer.net.transferproto.SimpleStateable
import com.programmersbox.filetransferer.net.transferproto.TransferProtoConstant
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.BroadcastDataType
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.BroadcastMsg
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.BroadcastTransferFileReq
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.BroadcastTransferFileResp
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


/**
 * [BroadcastSender] send UDP broadcast message ([BroadcastMsg]) on port [TransferProtoConstant.BROADCAST_SCANNER_PORT], [BroadcastReceiver] could receive this message when [BroadcastReceiver] and [BroadcastSender] in the same network.
 * And BroadcastSender will listen BroadcastReceiver request to create FileExplore connection, listen use UDP [TransferProtoConstant.BROADCAST_TRANSFER_SERVER_PORT] port.
 */
class BroadcastSender(
    private val deviceName: String,
    private val log: ILog,
    private val broadcastSendIntervalMillis: Long = 1000
) : SimpleObservable<BroadcastSenderObserver>, SimpleStateable<BroadcastSenderState> {

    override val state: AtomicReference<BroadcastSenderState> = AtomicReference(BroadcastSenderState.NoConnection)

    override val observers: LinkedBlockingDeque<BroadcastSenderObserver> = LinkedBlockingDeque()

    /**
     * Client transfer request server.
     */
    private val transferServer: IServer<BroadcastTransferFileReq, BroadcastTransferFileResp> by lazy {
        simplifyServer(
            requestType = BroadcastDataType.TransferFileReq.type,
            responseType = BroadcastDataType.TransferFileResp.type,
            log = log,
            onRequest = { _, rr, r, isNewRequest ->
                if (rr == null || r.version != TransferProtoConstant.VERSION) {
                    null
                } else {
                    if (isNewRequest) {
                        dispatchTransferReq(rr, r)
                    }
                    BroadcastTransferFileResp(deviceName = deviceName)
                }
            }
        )
    }

    private val sendFuture: AtomicReference<ScheduledFuture<*>?> by lazy {
        AtomicReference(null)
    }

    private val broadcastSenderTask: AtomicReference<ConnectionClientImpl?> by lazy {
        AtomicReference(null)
    }

    private val requestReceiverTask: AtomicReference<ConnectionServerImpl?> by lazy {
        AtomicReference(null)
    }

    private val closeObserver: NettyConnectionObserver by lazy {
        object : NettyConnectionObserver {
            override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {
                if (nettyState is NettyTaskState.ConnectionClosed || nettyState is NettyTaskState.Error) {
                    closeConnectionIfActive()
                }
            }

            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
        }
    }

    /**
     * Broadcast send task.
     */
    private val senderBroadcastTask: Runnable by lazy {
        Runnable {
            val state = getCurrentState()
            if (state is BroadcastSenderState.Active) {
                log.d(TAG, "Send broadcast.")
                broadcastSenderTask.get()?.requestSimplify<BroadcastMsg, Unit>(
                    type = BroadcastDataType.BroadcastMsg.type,
                    request = BroadcastMsg(
                        version = TransferProtoConstant.VERSION,
                        deviceName = deviceName
                    ),
                    retryTimes = 0,
                    targetAddress = InetSocketAddress(state.broadcastAddress, TransferProtoConstant.BROADCAST_SCANNER_PORT),
                    callback = object : IClientManager.RequestCallback<Unit> {
                        override fun onSuccess(
                            type: Int,
                            messageId: Long,
                            localAddress: InetSocketAddress?,
                            remoteAddress: InetSocketAddress?,
                            d: Unit
                        ) {}
                        override fun onFail(errorMsg: String) {}
                    }
                )
            } else {
                log.e(TAG, "Send broadcast fail, wrong state: $state")
            }
        }
    }

    override fun addObserver(o: BroadcastSenderObserver) {
        super.addObserver(o)
        o.onNewState(getCurrentState())
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    fun startBroadcastSender(
        localAddress: InetAddress,
        broadcastAddress: InetAddress,
        simpleCallback: SimpleCallback<Unit>) {
        val currentState = getCurrentState()
        if (currentState != BroadcastSenderState.NoConnection) {
            simpleCallback.onError("Wrong state: $currentState")
        }
        newState(BroadcastSenderState.Requesting)
        val hasInvokeCallback = AtomicBoolean(false)
        // Broadcast send task.
        val senderTask = NettyUdpConnectionTask(
            connectionType = ConnectionType.Connect(
                address = broadcastAddress,
                port = TransferProtoConstant.BROADCAST_SCANNER_PORT
            ),
            enableBroadcast = true
        ).withClient<ConnectionClientImpl>(log = log)
        this.broadcastSenderTask.get()?.stopTask()
        this.broadcastSenderTask.set(senderTask)

        // Receive client transfer file request task.
        val requestReceiverTask = NettyUdpConnectionTask(
            connectionType = ConnectionType.Bind(
                address = localAddress,
                port = TransferProtoConstant.BROADCAST_TRANSFER_SERVER_PORT
            )
        ).withServer<ConnectionServerImpl>(log = log)
        requestReceiverTask.registerServer(transferServer)
        this.requestReceiverTask.get()?.stopTask()
        this.requestReceiverTask.set(requestReceiverTask)

        senderTask.addObserver(object : NettyConnectionObserver {
            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
            override fun onNewState(senderState: NettyTaskState, task: INettyConnectionTask) {
                if (senderState is NettyTaskState.ConnectionClosed
                    || senderState is NettyTaskState.Error
                    || getCurrentState() !is BroadcastSenderState.Requesting
                ) {
                    // Broadcast sender task fail.
                    log.e(TAG, "Sender task error: $senderState, ${getCurrentState()}")
                    if (hasInvokeCallback.compareAndSet(false, true)) {
                        simpleCallback.onError(senderState.toString())
                    }
                    newState(BroadcastSenderState.NoConnection)
                    senderTask.removeObserver(this)
                    senderTask.stopTask()
                } else {
                    // Broadcast sender task success.
                    if (senderState is NettyTaskState.ConnectionActive) {
                        log.d(TAG, "Sender task connect success")
                        requestReceiverTask.addObserver(object : NettyConnectionObserver {

                            override fun onNewMessage(
                                localAddress: InetSocketAddress?,
                                remoteAddress: InetSocketAddress?,
                                msg: PackageData,
                                task: INettyConnectionTask
                            ) {}

                            override fun onNewState(
                                receiverState: NettyTaskState,
                                task: INettyConnectionTask
                            ) {
                                if (receiverState is NettyTaskState.ConnectionClosed
                                    || receiverState is NettyTaskState.Error
                                    || senderTask.getCurrentState() !is NettyTaskState.ConnectionActive
                                    || getCurrentState() !is BroadcastSenderState.Requesting
                                ) {
                                    // Receive client request task fail.
                                    log.d(TAG, "Request task bind fail: $receiverState, ${senderTask.getCurrentState()}, ${getCurrentState()}")
                                    if (hasInvokeCallback.compareAndSet(false, true)) {
                                        simpleCallback.onError(receiverState.toString())
                                    }
                                    newState(BroadcastSenderState.NoConnection)
                                    requestReceiverTask.removeObserver(this)
                                    requestReceiverTask.stopTask()
                                    senderTask.stopTask()
                                } else {
                                    // Receive client request task success.
                                    if (receiverState is NettyTaskState.ConnectionActive) {
                                        log.d(TAG, "Request task bind success")
                                        if (hasInvokeCallback.compareAndSet(false, true)) {
                                            simpleCallback.onSuccess(Unit)
                                        }
                                        // Send one broadcast each second (default)
                                        val senderFuture = taskScheduleExecutor.scheduleWithFixedDelay(
                                            senderBroadcastTask,
                                            500,
                                            broadcastSendIntervalMillis, TimeUnit.MILLISECONDS
                                        )
                                        this@BroadcastSender.sendFuture.get()?.cancel(true)
                                        this@BroadcastSender.sendFuture.set(senderFuture)
                                        newState(
                                            BroadcastSenderState.Active(
                                                broadcastAddress = broadcastAddress)
                                        )
                                        senderTask.addObserver(closeObserver)
                                        requestReceiverTask.addObserver(closeObserver)
                                    }
                                }
                            }
                        })
                        /**
                         * Step2: Start Receive client transfer file request task.
                         */
                        requestReceiverTask.startTask()
                        senderTask.removeObserver(this)
                    }
                }
            }
        })
        /**
         * Step1: Start broadcast sender task.
         */
        senderTask.startTask()
    }


    fun closeConnectionIfActive() {
        sendFuture.get()?.cancel(true)
        sendFuture.set(null)
        broadcastSenderTask.get()?.stopTask()
        broadcastSenderTask.set(null)
        requestReceiverTask.get()?.stopTask()
        requestReceiverTask.set(null)
        newState(BroadcastSenderState.NoConnection)
        clearObserves()
    }

    override fun onNewState(s: BroadcastSenderState) {
        super.onNewState(s)
        for (o in observers) {
            o.onNewState(s)
        }
    }

    private fun dispatchTransferReq(remoteAddress: InetSocketAddress, req: BroadcastTransferFileReq) {
        val rd = RemoteDevice(
            remoteAddress = remoteAddress,
            deviceName = req.deviceName
        )
        for (o in observers) {
            o.requestTransferFile(rd)
        }
    }

    companion object {
        private const val TAG = "BroadcastSender"
        private val taskScheduleExecutor: ScheduledExecutorService by lazy {
            Executors.newScheduledThreadPool(1) {
                Thread(it, "BroadcastTaskThread")
            }
        }
    }
}