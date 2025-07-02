package com.programmersbox.filetransferer.net.netty.extensions

import com.programmersbox.filetransferer.net.netty.INettyConnectionTask

class ConnectionServerClientImpl(
    val connectionTask: INettyConnectionTask,
    val serverManager: IServerManager,
    val clientManager: IClientManager
) : INettyConnectionTask by connectionTask, IServerManager by serverManager, IClientManager by clientManager