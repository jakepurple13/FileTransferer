package com.programmersbox.filetransferer.presentation.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.programmersbox.filetransferer.DefaultLogger
import com.programmersbox.filetransferer.net.netty.toInetAddress
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExplore
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExploreObserver
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExploreRequestHandler
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExploreState
import com.programmersbox.filetransferer.net.transferproto.fileexplore.Handshake
import com.programmersbox.filetransferer.net.transferproto.fileexplore.bindSuspend
import com.programmersbox.filetransferer.net.transferproto.fileexplore.connectSuspend
import com.programmersbox.filetransferer.net.transferproto.fileexplore.handshakeSuspend
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.DownloadFilesReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.DownloadFilesResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.ScanDirReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.ScanDirResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendFilesReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendFilesResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendMsgReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.requestMsgSuspend
import com.programmersbox.filetransferer.net.transferproto.fileexplore.waitClose
import com.programmersbox.filetransferer.net.transferproto.fileexplore.waitHandshake
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanClient
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.startQRCodeScanClientSuspend
import com.programmersbox.filetransferer.presentation.ConnectionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.runCatching

class ConnectionViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val d = savedStateHandle.toRoute<ConnectionScreen>()

    var connectionStatus: ConnectionStatus by mutableStateOf(ConnectionStatus.Connecting)

    private val fileTransferMutex: Mutex by lazy {
        Mutex(false)
    }

    val fileExplore = FileExplore(
        log = DefaultLogger,
        scanDirRequest = object : FileExploreRequestHandler<ScanDirReq, ScanDirResp> {
            override fun onRequest(isNew: Boolean, request: ScanDirReq): ScanDirResp? {
                return null
            }
        },
        sendFilesRequest = object : FileExploreRequestHandler<SendFilesReq, SendFilesResp> {
            override fun onRequest(
                isNew: Boolean,
                request: SendFilesReq
            ): SendFilesResp? {
                if (isNew) {
                    viewModelScope.launch {
                        downloadFiles(request.sendFiles, 8)
                    }
                }
                return SendFilesResp(bufferSize = 512 * 1024)
            }
        },
        downloadFileRequest = object :
            FileExploreRequestHandler<DownloadFilesReq, DownloadFilesResp> {
            override fun onRequest(
                isNew: Boolean,
                request: DownloadFilesReq
            ): DownloadFilesResp? {
                return null
            }
        }
    )

    init {
        viewModelScope.launch {
            initConnection()
        }
    }

    private suspend fun initConnection() {
        coroutineScope {
            val (remoteAddress, isServer, localAddress) = d

            launch(Dispatchers.IO) {
                if (isServer) {
                    // Server
                    runCatching {
                        withTimeout(5000L) {
                            fileExplore.bindSuspend(address = localAddress.toInetAddress())
                        }
                    }
                } else {
                    // Client, client retry 3 times.
                    var connectTimes = 3
                    var connectResult: Result<Unit>
                    do {
                        delay(200)
                        connectResult = runCatching {
                            fileExplore.connectSuspend(remoteAddress.toInetAddress())
                        }
                        if (connectResult.isSuccess) {
                            break
                        }
                    } while (--connectTimes > 0)
                    connectResult
                }
                    .onSuccess {
                        // Handshake, client request handshake, server wait handshake.
                        if (isServer) {
                            runCatching {
                                withTimeout(3000L) {
                                    fileExplore.waitHandshake()
                                }
                            }
                        } else {
                            runCatching {
                                fileExplore.handshakeSuspend()
                            }
                        }
                            .onSuccess { handshake ->
                                connectionStatus = ConnectionStatus.Connected(handshake)

                                fileExplore.addObserver(
                                    object : FileExploreObserver {
                                        override fun onNewState(state: FileExploreState) {}

                                        // New message coming.
                                        override fun onNewMsg(msg: SendMsgReq) {
                                            println(msg)
                                        }
                                    }
                                )
                                // Waiting connection close.
                                fileExplore.waitClose()
                                connectionStatus = ConnectionStatus.Closed
                            }
                            .onFailure {
                                connectionStatus = ConnectionStatus.Closed
                            }
                    }
                    .onFailure {
                        // Create connection fail.
                        connectionStatus = ConnectionStatus.Closed
                    }
            }
        }
    }

    fun sendMsg() {
        viewModelScope.launch {
            fileExplore.requestMsgSuspend("Hello!")
        }
    }

    suspend fun downloadFiles(files: List<FileExploreFile>, maxConnection: Int) {
        /*coroutineScope {
            val fixedFiles = files.filter { it.size > 0 }
            if (fixedFiles.isEmpty()) return@coroutineScope
            if (fileTransferMutex.isLocked) return@coroutineScope
            fileTransferMutex.lock()
            delay(150L)
            val result = withContext(Dispatchers.Main) {
                *//*val d = FileDownloaderDialog(
                    senderAddress = intent.getRemoteAddress(),
                    files = fixedFiles,
                    downloadDir = File(Settings.getDownloadDir()),
                    maxConnectionSize = maxConnection
                )
                this@FileTransportActivity.supportFragmentManager.showSimpleForceCoroutineResultDialogSuspend(
                    d
                )*//*
                runCatching {

                }
            }
            if (result is FileTransferResult.Error) {
                withContext(Dispatchers.Main) {
                    val d = NoOptionalDialog(
                        title = getString(R.string.file_transfer_error_title),
                        message = result.msg,
                        positiveButtonText = getString(R.string.dialog_positive)
                    )
                    this@FileTransportActivity.supportFragmentManager.showSimpleCancelableCoroutineResultDialogSuspend(
                        d
                    )
                }
            }
            fileTransferMutex.unlock()
        }*/
    }

    sealed class ConnectionStatus {
        data object Connecting : ConnectionStatus()

        data class Connected(val handshake: Handshake) : ConnectionStatus()

        data object Closed : ConnectionStatus()
    }
}