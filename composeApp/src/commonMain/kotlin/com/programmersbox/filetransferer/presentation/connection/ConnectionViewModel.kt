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
import com.programmersbox.filetransferer.net.transferproto.fileexplore.requestSendFilesSuspend
import com.programmersbox.filetransferer.net.transferproto.fileexplore.waitClose
import com.programmersbox.filetransferer.net.transferproto.fileexplore.waitHandshake
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.SenderFile
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanClient
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.startQRCodeScanClientSuspend
import com.programmersbox.filetransferer.presentation.ConnectionScreen
import com.programmersbox.filetransferer.readPlatformFile
import com.programmersbox.filetransferer.toFileExplore
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
                    // Server with improved retry mechanism
                    val maxRetries = 5
                    var retryCount = 0
                    var bindResult: Result<Unit>
                    var backoffDelay = 500L // Start with 500ms delay

                    do {
                        if (retryCount > 0) {
                            println("Server bind attempt $retryCount failed, retrying in ${backoffDelay}ms...")
                            delay(backoffDelay)
                            // Exponential backoff with a maximum of 8 seconds
                            backoffDelay = minOf(backoffDelay * 2, 8000L)
                        }

                        bindResult = runCatching {
                            withTimeout(5000L) {
                                fileExplore.bindSuspend(address = localAddress.toInetAddress())
                            }
                        }
                        retryCount++
                    } while (!bindResult.isSuccess && retryCount < maxRetries)

                    if (!bindResult.isSuccess) {
                        println("Server bind failed after $maxRetries attempts: ${bindResult.exceptionOrNull()}")
                        connectionStatus = ConnectionStatus.Closed
                        return@launch
                    }

                    bindResult
                } else {
                    // Client with improved retry mechanism
                    val maxRetries = 5
                    var retryCount = 0
                    var connectResult: Result<Unit>
                    var backoffDelay = 500L // Start with 500ms delay

                    do {
                        if (retryCount > 0) {
                            println("Client connection attempt $retryCount failed, retrying in ${backoffDelay}ms...")
                            delay(backoffDelay)
                            // Exponential backoff with a maximum of 8 seconds
                            backoffDelay = minOf(backoffDelay * 2, 8000L)
                        }

                        connectResult = runCatching {
                            fileExplore.connectSuspend(remoteAddress.toInetAddress())
                        }
                        retryCount++
                    } while (!connectResult.isSuccess && retryCount < maxRetries)

                    if (!connectResult.isSuccess) {
                        println("Client connection failed after $maxRetries attempts: ${connectResult.exceptionOrNull()}")
                        connectionStatus = ConnectionStatus.Closed
                        return@launch
                    }

                    connectResult
                }
                    .onSuccess {
                        // Handshake, client request handshake, server wait handshake.
                        if (isServer) {
                            runCatching {
                                withTimeout(5000L) { // Increased timeout for handshake
                                    fileExplore.waitHandshake()
                                }
                            }
                        } else {
                            // Client handshake with retry
                            val maxHandshakeRetries = 3
                            var handshakeRetryCount = 0
                            var handshakeResult: Result<Handshake>

                            do {
                                if (handshakeRetryCount > 0) {
                                    println("Handshake attempt $handshakeRetryCount failed, retrying...")
                                    delay(1000)
                                }

                                handshakeResult = runCatching {
                                    fileExplore.handshakeSuspend()
                                }
                                handshakeRetryCount++
                            } while (!handshakeResult.isSuccess && handshakeRetryCount < maxHandshakeRetries)

                            handshakeResult
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
                            .onFailure { error ->
                                println("Handshake failed: ${error.message}")
                                connectionStatus = ConnectionStatus.Closed
                            }
                    }
                    .onFailure { error ->
                        // Create connection fail.
                        println("Connection establishment failed: ${error.message}")
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
            /*val files = FileKit.openFilePicker(
                mode = FileKitMode.Multiple()
            )
                .orEmpty()
                *//*.map { readPlatformFile(it) }
                .map { file ->
                    FileExploreFile(
                        path = file.path,
                        name = file.name,
                        size = file.size(),
                        lastModify = System.currentTimeMillis()
                    )
                }*//*
                .map { toFileExplore(it) }
                .onEach { println(it) }*/

            val file = File(getDefaultDownloadDir(), "hello.txt")
            if(!file.exists()) file.createNewFile()
            file.writeText("Hello World!")

            val files = listOf(
                FileExploreFile(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModify = file.lastModified()
                )
            )

            //sendFiles(files)
            fileExplore.requestSendFilesSuspend(files)
        }
    }

    fun downloadFileExample() {
        viewModelScope.launch {
            //TODO: Figure out why this isn't working
            val files = FileKit.openFilePicker(
                mode = FileKitMode.Multiple()
            )
                .orEmpty()
                /*.map { readPlatformFile(it) }
                .map { file ->
                    FileExploreFile(
                        path = file.path,
                        name = file.name,
                        size = file.size(),
                        lastModify = System.currentTimeMillis()
                    )
                }*/
                .map { toFileExplore(it) }
                .onEach { println(it) }

            downloadFiles(files, 8)
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
                        println(fileSendStatus)
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
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val downloader = FileDownloadHandler(
                    senderAddress = d.remoteAddress.toInetAddress(),
                    files = fixedFiles,
                    downloadDir = File(getDefaultDownloadDir()),
                    maxConnectionSize = maxConnection,
                    updateState = { oldState ->
                        fileSendStatus = oldState(fileSendStatus)
                        println(fileSendStatus)
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
