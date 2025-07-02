package com.programmersbox.filetransferer.presentation.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.programmersbox.filetransferer.DefaultLogger
import com.programmersbox.filetransferer.getDefaultDownloadDir
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
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.SenderFile
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanClient
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.startQRCodeScanClientSuspend
import com.programmersbox.filetransferer.presentation.ConnectionScreen
import com.programmersbox.filetransferer.readPlatformFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absoluteFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Optional
import kotlin.runCatching

class ConnectionViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val d = savedStateHandle.toRoute<ConnectionScreen>()

    var connectionStatus: ConnectionStatus by mutableStateOf(ConnectionStatus.Connecting)
    var fileSendStatus: FileTransferDialogState by mutableStateOf(FileTransferDialogState())

    private val fileTransferMutex: Mutex by lazy {
        Mutex(false)
    }

    val fileExplore = FileExplore(
        log = DefaultLogger,
        scanDirRequest = object : FileExploreRequestHandler<ScanDirReq, ScanDirResp> {
            override fun onRequest(isNew: Boolean, request: ScanDirReq): ScanDirResp? {
                println("ScanDirReq: $request, isNew: $isNew")
                return null
            }
        },
        sendFilesRequest = object : FileExploreRequestHandler<SendFilesReq, SendFilesResp> {
            override fun onRequest(
                isNew: Boolean,
                request: SendFilesReq
            ): SendFilesResp? {
                println("SendFilesReq: $request, isNew: $isNew")
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
                println("DownloadFilesReq: $request, isNew: $isNew")
                if (isNew) {
                    viewModelScope.launch {
                        sendFiles(request.downloadFiles)
                    }
                }
                return DownloadFilesResp(8)
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

    fun sendFileExample() {
        viewModelScope.launch {
            //TODO: Figure out why this isn't working
            val files = FileKit.openFilePicker(
                mode = FileKitMode.Multiple()
            )
                .orEmpty()
                .map { readPlatformFile(it) }
                .map { file ->
                    FileExploreFile(
                        path = file.path,
                        name = file.name,
                        size = file.size(),
                        lastModify = System.currentTimeMillis()
                    )
                }
                .onEach { println(it) }

            sendFiles(files)
        }
    }

    suspend fun sendFiles(files: List<FileExploreFile>) {
        val fixedFiles = files.filter { it.size > 0 }
        val senderFiles = fixedFiles.map { SenderFile(File(it.path), it) }
        if (senderFiles.isEmpty()) return
        sendSenderFiles(senderFiles)
    }

    suspend fun sendSenderFiles(files: List<SenderFile>) {
        if (files.isEmpty()) return
        if (fileTransferMutex.isLocked) return
        fileTransferMutex.lock()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val sender = FileSenderHandler(
                    bindAddress = d.localAddress.toInetAddress(),
                    files = files,
                    updateState = { oldState ->
                        fileSendStatus = oldState(fileSendStatus)
                    },
                    onResult = {
                        println(it)
                    }
                )
                sender.send()
                sender
            }
                .onFailure { it.printStackTrace() }
        }
        /*if (result is FileTransferResult.Error) {
            withContext(Dispatchers.Main) {
                val d = NoOptionalDialog(
                    title = getString(R.string.file_transfer_error_title),
                    message = result.msg,
                    positiveButtonText = getString(R.string.dialog_positive)
                )
                this@FileTransportActivity.supportFragmentManager.showSimpleCancelableCoroutineResultDialogSuspend(d)
            }
        }*/
        fileTransferMutex.unlock()
    }

    suspend fun downloadFiles(files: List<FileExploreFile>, maxConnection: Int) {
        val fixedFiles = files.filter { it.size > 0 }
        if (fixedFiles.isEmpty()) return
        if (fileTransferMutex.isLocked) return
        fileTransferMutex.lock()
        delay(150L)
        val result = withContext(Dispatchers.Main) {
            runCatching {
                val downloader = FileDownloadHandler(
                    senderAddress = d.remoteAddress.toInetAddress(),
                    files = fixedFiles,
                    downloadDir = File(getDefaultDownloadDir()),
                    maxConnectionSize = maxConnection,
                    updateState = { oldState ->
                        fileSendStatus = oldState(fileSendStatus)
                    },
                    onResult = {
                        println(it)
                    }
                )
                downloader.download()
                downloader
            }.onFailure { it.printStackTrace() }
            /*val d = FileDownloaderDialog(
                senderAddress = intent.getRemoteAddress(),
                files = fixedFiles,
                downloadDir = File(Settings.getDownloadDir()),
                maxConnectionSize = maxConnection
            )
            this@FileTransportActivity.supportFragmentManager.showSimpleForceCoroutineResultDialogSuspend(d)*/
        }
        /*if (result is FileTransferResult.Error) {
            withContext(Dispatchers.Main) {
                val d = NoOptionalDialog(
                    title = getString(R.string.file_transfer_error_title),
                    message = result.msg,
                    positiveButtonText = getString(R.string.dialog_positive)
                )
                this@FileTransportActivity.supportFragmentManager.showSimpleCancelableCoroutineResultDialogSuspend(d)
            }
        }*/
        fileTransferMutex.unlock()
    }

    override fun onCleared() {
        super.onCleared()
        fileExplore.closeConnectionIfActive()
    }
}

sealed class ConnectionStatus {
    data object Connecting : ConnectionStatus()

    data class Connected(val handshake: Handshake) : ConnectionStatus()

    data object Closed : ConnectionStatus()
}

sealed class FileTransferResult {
    data class Error(val msg: String) : FileTransferResult()
    data object Cancel : FileTransferResult()
    data object Finished : FileTransferResult()
}

data class FileTransferDialogState(
    val transferFile: Optional<FileExploreFile> = Optional.empty(),
    val process: Long = 0L,
    val speedString: String = "",
    val finishedFiles: List<FileExploreFile> = emptyList()
)
