package com.programmersbox.filetransferer.net.transferproto.p2pconn

import com.programmersbox.filetransferer.net.ILog
import com.programmersbox.filetransferer.net.netty.INettyConnectionTask
import com.programmersbox.filetransferer.net.netty.NettyConnectionObserver
import com.programmersbox.filetransferer.net.netty.NettyTaskState
import com.programmersbox.filetransferer.net.netty.PackageData
import com.programmersbox.filetransferer.net.netty.extensions.*
import com.programmersbox.filetransferer.net.netty.tcp.NettyTcpClientConnectionTask
import com.programmersbox.filetransferer.net.netty.tcp.NettyTcpServerConnectionTask
import com.programmersbox.filetransferer.net.transferproto.SimpleCallback
import com.programmersbox.filetransferer.net.transferproto.SimpleObservable
import com.programmersbox.filetransferer.net.transferproto.SimpleStateable
import com.programmersbox.filetransferer.net.transferproto.TransferProtoConstant
import com.programmersbox.filetransferer.net.transferproto.p2pconn.model.P2pDataType
import com.programmersbox.filetransferer.net.transferproto.p2pconn.model.P2pHandshakeReq
import com.programmersbox.filetransferer.net.transferproto.p2pconn.model.P2pHandshakeResp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wifi p2p connection.
 * P2P group owner as Server, after Wifi p2p is ok, server bind TCP [TransferProtoConstant.P2P_GROUP_OWNER_PORT], waiting client connect.
 * Only one client could connect to server.
 * After connection created, client will send [P2pDataType.HandshakeReq] [P2pHandshakeReq] to server, server will check handshake information.
 * When handshake is ok, client and server both could send [P2pHandshakeReq] to other side and create FileExplore connection.
 */
class P2pConnection(
    private val currentDeviceName: String,
    private val log: ILog
) : SimpleObservable<P2pConnectionObserver>, SimpleStateable<P2pConnectionState> {

    override val state: AtomicReference<P2pConnectionState> = AtomicReference(P2pConnectionState.NoConnection)

    override val observers: LinkedBlockingDeque<P2pConnectionObserver> by lazy {
        LinkedBlockingDeque()
    }

    private val handShakeServer: IServer<P2pHandshakeReq, P2pHandshakeResp> by lazy {
        simplifyServer(
            requestType = P2pDataType.HandshakeReq.type,
            responseType = P2pDataType.HandshakeResp.type,
            log = log,
            onRequest = { ld, rd, r, isNewRequest ->
                // Client request handshake, check handshake information and update state.
                if (getCurrentState() is P2pConnectionState.Active
                    && r.version == TransferProtoConstant.VERSION) {
                    if (isNewRequest) {
                        log.d(TAG, "Receive handshake: ld -> $ld, rd -> $rd, r -> $r")
                        if (ld is InetSocketAddress
                            && rd is InetSocketAddress
                            && getCurrentState() is P2pConnectionState.Active) {
                            newState(P2pConnectionState.Handshake(
                                localAddress = ld,
                                remoteAddress = rd,
                                remoteDeviceName = r.deviceName
                            ))
                        } else {
                            closeConnectionIfActive()
                        }
                    }
                    P2pHandshakeResp(
                        deviceName = currentDeviceName
                    )
                } else {
                    null
                }
            },
        )
    }

    private val transferFileServer: IServer<Unit, Unit> by lazy {
        simplifyServer(
            requestType = P2pDataType.TransferFileReq.type,
            responseType = P2pDataType.TransferFileResp.type,
            log = log,
            onRequest = { _, _, _, isNewRequest ->
                // Request transfer file, Server and Client both can request.
                if (isNewRequest) {
                    log.d(TAG, "Receive transfer file request.")
                    dispatchTransferFile(true)
                }
            }
        )
    }

    private val closeServer: IServer<Unit, Unit> by lazy {
        simplifyServer(
            requestType = P2pDataType.CloseConnReq.type,
            responseType = P2pDataType.CloseConnResp.type,
            log = log,
            onRequest = { _, _, _, isNewRequest ->
                if (isNewRequest) {
                    log.d(TAG, "Receive close request.")
                    Dispatchers.IO.asExecutor().runCatching {
                        Thread.sleep(100)
                        closeConnectionIfActive()
                    }
                }
            }
        )
    }

    private val activeServerNettyTask: AtomicReference<NettyTcpServerConnectionTask?> by lazy {
        AtomicReference(null)
    }

    private val activeCommunicationNettyTask: AtomicReference<ConnectionServerClientImpl?> by lazy {
        AtomicReference(null)
    }

    private val activeCommunicationTaskObserver: NettyConnectionObserver by lazy {
        object : NettyConnectionObserver {
            override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {
                if (nettyState is NettyTaskState.ConnectionClosed || nettyState is NettyTaskState.Error) {
                    log.d(TAG, "Connection closed: $nettyState")
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

    override fun addObserver(o: P2pConnectionObserver) {
        super.addObserver(o)
        o.onNewState(getCurrentState())
    }

    fun bind(address: InetAddress, simpleCallback: SimpleCallback<Unit>) {
        val currentState = getCurrentState()
        if (currentState != P2pConnectionState.NoConnection) {
            simpleCallback.onError("Wrong current state: $currentState")
            return
        }
        newState(P2pConnectionState.Requesting)
        val hasInvokeCallback = AtomicBoolean(false)
        // TCP Server task, waiting client to connect，only one client can connect.
        var serverTask: NettyTcpServerConnectionTask? = null
        // TCP connection task， communicat with client.
        var communicationTask: ConnectionServerClientImpl? = null
        val stateCallback = object : NettyConnectionObserver {
            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
            override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {
                if (nettyState is NettyTaskState.Error
                    || nettyState is NettyTaskState.ConnectionClosed
                    || getCurrentState() !is P2pConnectionState.Requesting
                ) {
                    // Create server connection or client connection fail.
                    if (hasInvokeCallback.compareAndSet(false, true)) {
                        simpleCallback.onError(nettyState.toString())
                    }
                    serverTask?.stopTask()
                    communicationTask?.stopTask()
                    log.e(TAG, "Bind $address fail: $nettyState, ${getCurrentState()}")
                    task.removeObserver(this)
                    newState(P2pConnectionState.NoConnection)
                } else {
                    if (task !is NettyTcpServerConnectionTask && nettyState is NettyTaskState.ConnectionActive) {
                        // Client connection create success.
                        task.addObserver(activeCommunicationTaskObserver)
                        activeCommunicationNettyTask.set(communicationTask)
                        if (hasInvokeCallback.compareAndSet(false, true)) {
                            simpleCallback.onSuccess(Unit)
                        }
                        newState(
                            P2pConnectionState.Active(
                                localAddress = nettyState.channel.localAddress() as? InetSocketAddress,
                                remoteAddress = nettyState.channel.remoteAddress() as? InetSocketAddress
                            )
                        )
                        log.d(TAG, "Bind $address success: $nettyState")
                        task.removeObserver(this)
                    }
                    if (task is NettyTcpServerConnectionTask && nettyState is NettyTaskState.ConnectionActive) {
                        // Server connection create success.
                        activeServerNettyTask.set(serverTask)
                        log.d(TAG, "Server is active.")
                        task.removeObserver(this)
                    }
                }
            }
        }
        val hasClientConnection = AtomicBoolean(false)
        serverTask = NettyTcpServerConnectionTask(
            bindAddress = address,
            bindPort = TransferProtoConstant.P2P_GROUP_OWNER_PORT,
            newClientTaskCallback = { client ->
                // New client coming.
                if (hasClientConnection.compareAndSet(false, true)) {
                    /**
                     * Step2: Handle client connection.
                     */
                    val fixedClientConnection = client.withServer<ConnectionServerImpl>(log = log)
                        .withClient<ConnectionServerClientImpl>(log = log)
                    fixedClientConnection.registerServer(handShakeServer)
                    fixedClientConnection.registerServer(transferFileServer)
                    fixedClientConnection.registerServer(closeServer)
                    fixedClientConnection.addObserver(stateCallback)
                    communicationTask = fixedClientConnection
                } else {
                    client.stopTask()
                }
            }
        )
        serverTask.addObserver(stateCallback)

        /**
         * Step1: Start server task.
         */
        serverTask.startTask()
    }

    fun connect(serverAddress: InetAddress, simpleCallback: SimpleCallback<Unit>) {
        val currentState = getCurrentState()
        if (currentState != P2pConnectionState.NoConnection) {
            simpleCallback.onError("Wrong current state: $currentState")
            return
        }
        newState(P2pConnectionState.Requesting)
        // Connect to server task.
        val clientTask = NettyTcpClientConnectionTask(
            serverAddress = serverAddress,
            serverPort = TransferProtoConstant.P2P_GROUP_OWNER_PORT
        ).withServer<ConnectionServerImpl>(log = log).withClient<ConnectionServerClientImpl>(log = log)
        clientTask.registerServer(transferFileServer)
        clientTask.registerServer(closeServer)
        val hasInvokeCallback = AtomicBoolean(false)
        clientTask.addObserver(
            object : NettyConnectionObserver {
                override fun onNewMessage(
                    localAddress: InetSocketAddress?,
                    remoteAddress: InetSocketAddress?,
                    msg: PackageData,
                    task: INettyConnectionTask
                ) {}
                override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {

                    if (nettyState is NettyTaskState.Error
                        || nettyState is NettyTaskState.ConnectionClosed
                        || getCurrentState() !is P2pConnectionState.Requesting) {
                        // Connect to server fail.
                        if (hasInvokeCallback.compareAndSet(false, true)) {
                            simpleCallback.onError(nettyState.toString())
                        }
                        task.removeObserver(this)
                        clientTask.stopTask()
                        log.e(TAG, "Connect $serverAddress fail: $nettyState, ${getCurrentState()}")
                        newState(P2pConnectionState.NoConnection)
                    } else {
                        // Connect to server success.
                        if (nettyState is NettyTaskState.ConnectionActive) {
                            task.addObserver(activeCommunicationTaskObserver)
                            activeCommunicationNettyTask.set(clientTask)
                            if (hasInvokeCallback.compareAndSet(false, true)) {
                                simpleCallback.onSuccess(Unit)
                            }
                            newState(
                                P2pConnectionState.Active(
                                    localAddress = nettyState.channel.localAddress() as? InetSocketAddress,
                                    remoteAddress = nettyState.channel.remoteAddress() as? InetSocketAddress
                                )
                            )
                            task.removeObserver(this)
                            log.d(TAG, "Connection $serverAddress success.")
                            /**
                             * Step2: Request handshake
                             */
                            requestHandshake(clientTask)
                        }
                    }
                }
            }
        )
        /**
         * Step1: Request connect to server task.
         */
        clientTask.startTask()
    }

    fun requestTransferFile(simpleCallback: SimpleCallback<P2pConnectionState.Handshake>) {
        assertConnectionAndState(
            onSuccess = { activeConnection, handshake ->
                activeConnection.requestSimplify<Unit, Unit>(
                    type = P2pDataType.TransferFileReq.type,
                    request = Unit,
                    callback = object : IClientManager.RequestCallback<Unit> {

                        override fun onSuccess(
                            type: Int,
                            messageId: Long,
                            localAddress: InetSocketAddress?,
                            remoteAddress: InetSocketAddress?,
                            d: Unit
                        ) {
                            simpleCallback.onSuccess(handshake)
                            dispatchTransferFile(false)
                        }

                        override fun onFail(errorMsg: String) {
                            simpleCallback.onError(errorMsg)
                        }

                    }
                )
            },
            onError = {
                simpleCallback.onError(it)
            }
        )
    }

    fun requestClose(simpleCallback: SimpleCallback<Unit>) {
        assertConnectionAndState(
            onSuccess = { activeConnection, _ ->
                activeConnection.requestSimplify(
                    type = P2pDataType.CloseConnReq.type,
                    request = Unit,
                    callback = object : IClientManager.RequestCallback<Unit> {

                        override fun onSuccess(
                            type: Int,
                            messageId: Long,
                            localAddress: InetSocketAddress?,
                            remoteAddress: InetSocketAddress?,
                            d: Unit
                        ) {
                            simpleCallback.onSuccess(Unit)
                        }

                        override fun onFail(errorMsg: String) {
                            simpleCallback.onError(errorMsg)
                        }

                    }
                )
            },
            onError = { errorMsg ->
                simpleCallback.onError(errorMsg)
            }
        )
    }

    fun closeConnectionIfActive() {
        activeCommunicationNettyTask.get()?.let {
            it.stopTask()
            activeCommunicationNettyTask.set(null)
        }
        activeServerNettyTask.get()?.let {
            it.stopTask()
            activeServerNettyTask.set(null)
        }
        newState(P2pConnectionState.NoConnection)
        clearObserves()
    }

    private fun assertConnectionAndState(
        onSuccess: (activeConnection: ConnectionServerClientImpl, handshake: P2pConnectionState.Handshake) -> Unit,
        onError: (msg: String) -> Unit
    ) {
        val activeConnection = getActiveCommunicationTask()
        if (activeConnection == null) {
            onError("Active connection is null")
            return
        }
        val state = getCurrentState()
        if (state !is P2pConnectionState.Handshake) {
            onError("Current is not handshake: $state")
            return
        }
        onSuccess(activeConnection, state)
    }

    /**
     * Client request hand shake.
     */
    private fun requestHandshake(client: ConnectionServerClientImpl) {
        client.requestSimplify(
            type = P2pDataType.HandshakeReq.type,
            request = P2pHandshakeReq(
                version = TransferProtoConstant.VERSION,
                deviceName = currentDeviceName
            ),
            callback = object : IClientManager.RequestCallback<P2pHandshakeResp> {
                override fun onSuccess(
                    type: Int,
                    messageId: Long,
                    localAddress: InetSocketAddress?,
                    remoteAddress: InetSocketAddress?,
                    d: P2pHandshakeResp
                ) {
                    log.d(TAG, "Handshake request success: $d!!")
                    if (localAddress is InetSocketAddress
                        && remoteAddress is InetSocketAddress
                        && getCurrentState() is P2pConnectionState.Active) {
                        // Client request handshake success.
                        newState(P2pConnectionState.Handshake(
                            localAddress = localAddress,
                            remoteAddress = remoteAddress,
                            remoteDeviceName = d.deviceName
                        ))
                    } else {
                        // Client request handshake fail.
                        closeConnectionIfActive()
                    }
                }

                override fun onFail(errorMsg: String) {
                    log.e(TAG, "Handshake request error: $errorMsg")
                    closeConnectionIfActive()
                }
            }
        )
    }

    override fun onNewState(s: P2pConnectionState) {
        for (o in observers) {
            o.onNewState(s)
        }
    }

    private fun dispatchTransferFile(isReceiver: Boolean) {
        val currentState = getCurrentState()
        if (currentState is P2pConnectionState.Handshake) {
            for (o in observers) {
                o.requestTransferFile(currentState, isReceiver)
            }
        }
    }

    private fun getActiveCommunicationTask(): ConnectionServerClientImpl? =
        activeCommunicationNettyTask.get()

    companion object {
        private const val TAG = "P2pConnection"
    }
}