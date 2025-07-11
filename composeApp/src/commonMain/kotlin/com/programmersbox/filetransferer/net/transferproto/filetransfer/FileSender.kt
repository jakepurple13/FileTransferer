package com.programmersbox.filetransferer.net.transferproto.filetransfer

import com.programmersbox.filetransferer.net.*
import com.programmersbox.filetransferer.net.netty.INettyConnectionTask
import com.programmersbox.filetransferer.net.netty.NettyConnectionObserver
import com.programmersbox.filetransferer.net.netty.NettyTaskState
import com.programmersbox.filetransferer.net.netty.PackageData
import com.programmersbox.filetransferer.net.netty.extensions.ConnectionClientImpl
import com.programmersbox.filetransferer.net.netty.extensions.ConnectionServerClientImpl
import com.programmersbox.filetransferer.net.netty.extensions.IClientManager
import com.programmersbox.filetransferer.net.netty.extensions.IServer
import com.programmersbox.filetransferer.net.netty.extensions.requestSimplify
import com.programmersbox.filetransferer.net.netty.extensions.simplifyServer
import com.programmersbox.filetransferer.net.netty.extensions.withClient
import com.programmersbox.filetransferer.net.netty.extensions.withServer
import com.programmersbox.filetransferer.net.netty.tcp.NettyTcpServerConnectionTask
import com.programmersbox.filetransferer.net.transferproto.SimpleObservable
import com.programmersbox.filetransferer.net.transferproto.SimpleStateable
import com.programmersbox.filetransferer.net.transferproto.TransferProtoConstant
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.DownloadReq
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.ErrorReq
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.FileTransferDataType
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.SenderFile
import kotlinx.coroutines.*
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * FileSender could send multiple files one time, single file sends see [SingleFileSender], single file contains multiple fragments,
 * one fragment uses a TCP connection to sends file's fragment see [SingleFileSender.SingleFileFragmentSender]
 */
class FileSender(
    private val files: List<SenderFile>,
    private val bindAddress: InetAddress,
    private val anchorBufferDurationInMillis: Long = 200L,
    private val log: ILog
) : SimpleObservable<FileTransferObserver>, SimpleStateable<FileTransferState> {

    override val observers: LinkedBlockingDeque<FileTransferObserver> = LinkedBlockingDeque()

    override val state: AtomicReference<FileTransferState> = AtomicReference(FileTransferState.NotExecute)

    private val serverTask: AtomicReference<NettyTcpServerConnectionTask?> by lazy {
        AtomicReference(null)
    }

    /**
     * Waiting send file tasks.
     */
    private val waitingSenders: LinkedBlockingDeque<SingleFileSender> = LinkedBlockingDeque()

    /**
     * Sending file task.
     */
    private val workingSender: AtomicReference<SingleFileSender?> by lazy {
        AtomicReference(null)
    }

    /**
     * Finished file send tasks.
     */
    private val finishedSenders: LinkedBlockingDeque<SingleFileSender> = LinkedBlockingDeque()

    private val unhandledFragmentSenderRequest: LinkedBlockingDeque<UnhandledFragmentRequest> = LinkedBlockingDeque()
    override fun addObserver(o: FileTransferObserver) {
        super.addObserver(o)
        o.onNewState(state.get())
    }

    /**
     * Start send files, sender as server.
     */
    @Synchronized
    fun start() {
        if (getCurrentState() is FileTransferState.NotExecute) {
            newState(FileTransferState.Started)
            waitingSenders.clear()
            // Single file send handle by SingleFileSender
            for (f in files) {
                waitingSenders.add(SingleFileSender(f))
            }
            // Move first file send task to sending task.
            doNextSender(null)

            // Start server with retry mechanism
            startServerWithRetry(1, 1000)
        }
    }

    /**
     * Start server with retry mechanism
     * @param attempt Current attempt number
     * @param delayMs Delay before next retry in milliseconds
     */
    private fun startServerWithRetry(attempt: Int, delayMs: Long) {
        // File sender server, waiting clients request.
        val serverTask = NettyTcpServerConnectionTask(
            bindAddress = bindAddress,
            bindPort = TransferProtoConstant.FILE_TRANSFER_PORT,
            newClientTaskCallback = { clientTask ->
                // New client coming.
                assertActive(
                    notActive = { clientTask.stopTask() }
                ) {
                    synchronized(this) {
                        val workingSender = workingSender.get()
                        if (workingSender != null) {
                            // Working file send task handle client connection.
                            workingSender.newChildTask(clientTask)
                        } else {
                            clientTask.stopTask()
                            val msg = "No working sender to handle clientTask."
                            log.e(TAG, msg)
                            errorStateIfActive(msg)
                        }
                    }
                }
            }
        )
        this.serverTask.set(serverTask)
        serverTask.addObserver(object : NettyConnectionObserver {
            override fun onNewState(nettyState: NettyTaskState, task: INettyConnectionTask) {
                if (nettyState is NettyTaskState.Error
                    || nettyState is NettyTaskState.ConnectionClosed) {
                    // Server connect error.
                    if (getCurrentState() is FileTransferState.Started) {
                        val maxRetries = 5
                        if (attempt < maxRetries) {
                            // Retry with exponential backoff
                            log.d(TAG, "Server bind attempt $attempt failed, retrying in ${delayMs}ms...")
                            serverTask.stopTask()

                            // Schedule retry with exponential backoff
                            Dispatchers.IO.asExecutor().execute {
                                Thread.sleep(delayMs)
                                startServerWithRetry(attempt + 1, delayMs * 2)
                            }
                        } else {
                            // Max retries reached, report error
                            val errorMsg = "Bind address failed after $maxRetries attempts: $nettyState, throwable: ${(nettyState as? NettyTaskState.Error)?.throwable}, ${getCurrentState()}"
                            log.e(TAG, errorMsg)
                            errorStateIfActive(errorMsg)
                        }
                    }
                } else {
                    // Server connect success.
                    if (nettyState is NettyTaskState.ConnectionActive) {
                        log.d(TAG, "Bind address success: $nettyState")
                    }
                }
            }

            override fun onNewMessage(
                localAddress: InetSocketAddress?,
                remoteAddress: InetSocketAddress?,
                msg: PackageData,
                task: INettyConnectionTask
            ) {}
        })
        // Start server task.
        serverTask.startTask()
    }

    @Synchronized
    fun cancel() {
        if (getCurrentState() !is FileTransferState.NotExecute) {
            assertActive {
                newState(FileTransferState.Canceled)
            }
            closeNoneFinishedSenders("User canceled", true)
            closeConnectionIfActive()
        }
    }

    private fun dispatchProgressUpdate(hasSentSize: Long, file: SenderFile) {
        assertActive {
            for (o in observers) {
                o.onProgressUpdate(file.exploreFile, hasSentSize)
            }
        }
    }
    private fun closeNoneFinishedSenders(reason: String, reportRemote: Boolean) {
        workingSender.get()?.onCanceled(reason, reportRemote)
        workingSender.set(null)
        waitingSenders.clear()
    }

    private fun doNextSender(finishedSender: SingleFileSender?) {
        synchronized(this) {
            assertActive {
                if (finishedSender != null) {
                    finishedSenders.add(finishedSender)
                    // Notify observers single file send finished.
                    for (o in observers) {
                        o.onEndFile(finishedSender.file.exploreFile)
                    }
                }
                workingSender.set(null)
                // Move waiting task to working.
                val targetSender = waitingSenders.pollFirst()
                if (targetSender != null) {
                    targetSender.onActive()
                    workingSender.set(targetSender)
                    // Notify observers single file send start.
                    for (o in observers) {
                        o.onStartFile(targetSender.file.exploreFile)
                    }
                } else {
                    // No waiting file task, all files send finished.
                    newState(FileTransferState.Finished)
                    closeConnectionIfActive()
                }
            }
        }
    }

    private fun errorStateIfActive(errorMsg: String) {
        assertActive {
            newState(FileTransferState.Error(errorMsg))
        }
        closeNoneFinishedSenders(errorMsg, true)
        closeConnectionIfActive()
    }

    private fun remoteErrorStateIfActive(errorMsg: String) {
        assertActive {
            newState(FileTransferState.RemoteError(errorMsg))
        }
        closeNoneFinishedSenders(errorMsg, false)
        closeConnectionIfActive()
    }

    private fun assertActive(notActive: (() -> Unit)? = null, active: () -> Unit) {
        if (getCurrentState() == FileTransferState.Started) {
            active()
        } else {
            notActive?.invoke()
        }
    }

    private fun closeConnectionIfActive() {
        for (uf in unhandledFragmentSenderRequest) {
            uf.connection.stopTask()
        }
        unhandledFragmentSenderRequest.clear()
        serverTask.get()?.stopTask()
        serverTask.set(null)
    }

    override fun onNewState(s: FileTransferState) {
        super.onNewState(s)
        for (o in observers) {
            o.onNewState(s)
        }
    }

    private inner class SingleFileSender(val file: SenderFile) {

        private val randomAccessFile: AtomicReference<RandomAccessFile?> by lazy { AtomicReference(null) }

        private val isSingleFileSenderExecuted: AtomicBoolean by lazy {
            AtomicBoolean(false)
        }

        private val isSingleFileSenderCanceled: AtomicBoolean by lazy {
            AtomicBoolean(false)
        }

        private val isSingleFileSenderFinished: AtomicBoolean by lazy {
            AtomicBoolean(false)
        }

        private val singleFileHasSentSize: AtomicLong by lazy {
            AtomicLong(0L)
        }

        private val fragmentSenders: LinkedBlockingDeque<SingleFileFragmentSender> by lazy {
            LinkedBlockingDeque()
        }

        /**
         * SingleFileSender move to working task from waiting tasks.
         */
        fun onActive() {
            if (!isSingleFileSenderCanceled.get() && !isSingleFileSenderFinished.get() && isSingleFileSenderExecuted.compareAndSet(false, true)) {
                try {
                    // Open real files for next sending.
                    randomAccessFile.get()?.close()
                    val randomAccessFile = RandomAccessFile(file.realFile, "r")
                    this.randomAccessFile.set(randomAccessFile)
                    // Check unhandled fragments task.
                    checkUnhandledFragment()
                } catch (e: Throwable) {
                    val msg = "Read file: $file error: ${e.message}"
                    log.d(TAG, msg)
                    errorStateIfActive(msg)
                }
            }
        }

        /**
         * Handle client request.
         * Each client request download a fragment of single file, single file splits multiple fragments to sending.
         * File's fragment sending handle by [SingleFileFragmentSender]
         */
        fun newChildTask(task: NettyTcpServerConnectionTask.ChildConnectionTask) {
            assertSingleFileSenderActive(notActive = {
                task.stopTask()
                val msg = "Single task is not active, can't deal child task"
                log.e(TAG, msg)
                errorStateIfActive(msg)
            }
            ) {
                val randomAccessFile = this.randomAccessFile.get()
                if (randomAccessFile != null) {
                    // Create new SingleFileFragmentSender to handle client's request.
                    fragmentSenders.add(SingleFileFragmentSender(randomAccessFile = randomAccessFile, task = task))
                } else {
                    task.stopTask()
                    val msg = "File handle is null"
                    log.e(TAG, msg)
                    errorStateIfActive(msg)
                }
                checkUnhandledFragment()
            }
        }

        fun receiveUnhandedConnection(fragmentSender: SingleFileFragmentSender, downloadReq: DownloadReq, connection: ConnectionServerClientImpl?) {
            fragmentSenders.remove(fragmentSender)
            if (connection != null) {
                unhandledFragmentSenderRequest.add(UnhandledFragmentRequest(
                    request = downloadReq,
                    connection = connection
                ))
            }
        }

        fun onCanceled(reason: String, reportRemote: Boolean) {
            if (isSingleFileSenderExecuted.get() && !isSingleFileSenderFinished.get() && isSingleFileSenderCanceled.compareAndSet(false, true)) {
                if (reportRemote) {
                    for (fs in fragmentSenders) {
                        fs.sendRemoteError(reason)
                    }
                }
                for (fs in fragmentSenders){
                    fs.closeConnectionIfActive()
                }
                fragmentSenders.clear()
                recycleResource()
            }
        }

        /**
         * Find out unhandled fragment requests which need current SingleFileSender handle.
         */
        private fun checkUnhandledFragment() {
            val randomAccessFile = randomAccessFile.get()
            if (randomAccessFile != null) {
                val needMeHandle = unhandledFragmentSenderRequest.filter { it.request.file == file.exploreFile }
                if (needMeHandle.isNotEmpty()) {
                    log.d(TAG, "Need me handle: $needMeHandle")
                }
                for (h in needMeHandle) {
                    unhandledFragmentSenderRequest.remove(h)
                    fragmentSenders.add(
                        SingleFileFragmentSender(
                            randomAccessFile = randomAccessFile,
                            downloadReq = h.request,
                            connection = h.connection
                        )
                    )
                }
            }
        }

        /**
         * Sending progress update.
         */
        private fun updateProgress(sentSize: Long) {
            val hasSentSize = singleFileHasSentSize.addAndGet(sentSize)
            // Notify progress to observers.
            dispatchProgressUpdate(hasSentSize, file)
            if (hasSentSize >= file.exploreFile.size) {
                // Current SingleFileSender finished.
                onFinished()
            }
        }

        private fun onFinished() {
            synchronized(this@FileSender) {
                assertSingleFileSenderActive {
                    if (isSingleFileSenderFinished.compareAndSet(false, true)) {
                        log.d(TAG, "File: ${file.exploreFile.name} send success!!!")
                        recycleResource()
                        // Start next SingleFileSender.
                        doNextSender(this)
                    }
                }
            }
        }

        private fun recycleResource() {
            try {
                randomAccessFile.get()?.let {
                    it.close()
                    randomAccessFile.set(null)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        private fun assertSingleFileSenderActive(notActive: (() -> Unit)? = null, active: () -> Unit) {
            if (!isSingleFileSenderFinished.get() && !isSingleFileSenderCanceled.get() && isSingleFileSenderExecuted.get()) {
                active()
            } else {
                notActive?.invoke()
            }
        }

        private inner class SingleFileFragmentSender : CoroutineScope  {

            override val coroutineContext: CoroutineContext = Dispatchers.IO + Job()

            private val serverClientTask: AtomicReference<ConnectionServerClientImpl?> by lazy {
                AtomicReference(null)
            }

            private val bufferSize: AtomicInteger by lazy {
                AtomicInteger(DEFAULT_FILE_SEND_BUFFER_SIZE)
            }

            private val randomAccessFile: RandomAccessFile

            private val isFragmentFinished: AtomicBoolean by lazy {
                AtomicBoolean(false)
            }

            private val closeObserver: NettyConnectionObserver by lazy {
                object : NettyConnectionObserver {
                    override fun onNewState(
                        nettyState: NettyTaskState,
                        task: INettyConnectionTask
                    ) {
                        if ((nettyState is NettyTaskState.Error || nettyState is NettyTaskState.ConnectionClosed) && !isFragmentFinished.get()) {
                            val msg = "Connection closed: $nettyState"
                            log.e(TAG, msg)
                            errorStateIfActive(msg)
                        }
                    }

                    override fun onNewMessage(
                        localAddress: InetSocketAddress?,
                        remoteAddress: InetSocketAddress?,
                        msg: PackageData,
                        task: INettyConnectionTask
                    ) {
                    }
                }
            }

            private val downloadReq: AtomicReference<DownloadReq?> by lazy {
                AtomicReference(null)
            }

            private val downloadReqServer: IServer<DownloadReq, Unit> by lazy {
                simplifyServer(
                    requestType = FileTransferDataType.DownloadReq.type,
                    responseType = FileTransferDataType.DownloadResp.type,
                    log = log,
                    onRequest = { _, _, r, isNew ->
                        // Client send needing file's fragment information.
                        if (isNew) {
                            // Check sending file.
                            if (downloadReq.get() == null && r.file == file.exploreFile) {
                                // File's fragment start.
                                val startIndex = r.start
                                // File's fragment end.
                                val endIndex = r.end
                                if (startIndex < 0 || endIndex < 0 || startIndex > endIndex || endIndex > r.file.size) {
                                    val msg = "Wrong file info: $r"
                                    log.e(TAG, msg)
                                    errorStateIfActive(msg)
                                    return@simplifyServer
                                }
                                serverClientTask.get()?.registerServer(finishReqServer)
                                downloadReq.set(r)
                                // Start fragment sending.
                                startSendData(r)
                            } else {
                                // Not current SingleFileSender handle, move to unhandled connection, waiting next SingleFileSender to handle.
                                log.e(TAG, "Receive unknown request: $r")
                                val connectionTask = serverClientTask.get()
                                connectionTask?.unregisterServer(downloadReqServer)
                                connectionTask?.unregisterServer(errorReqServer)
                                connectionTask?.removeObserver(closeObserver)
                                serverClientTask.set(null)
                                receiveUnhandedConnection(this, r, connectionTask)
                            }
                        }
                        Unit
                    }
                )
            }

            private val errorReqServer: IServer<ErrorReq, Unit> by lazy {
                simplifyServer(
                    requestType = FileTransferDataType.ErrorReq.type,
                    responseType = FileTransferDataType.ErrorResp.type,
                    log = log,
                    onRequest = { _, _, r, isNew ->
                        log.e(TAG, "Receive remote error request: $r")
                        if (isNew) {
                            remoteErrorStateIfActive(r.errorMsg)
                        }
                    }
                )
            }

            private val finishReqServer: IServer<Unit, Unit> by lazy {
                simplifyServer(
                    requestType = FileTransferDataType.FinishedReq.type,
                    responseType = FileTransferDataType.FinishedResp.type,
                    log = log,
                    onRequest = { _, _, _, isNew ->
                        if (isNew) {
                            log.d(TAG, "Receive download finish request.")
                        }
                    }
                )
            }
            constructor(randomAccessFile: RandomAccessFile,
                        task: NettyTcpServerConnectionTask.ChildConnectionTask) {
                this.randomAccessFile = randomAccessFile
                val serverClientTask = task.withClient<ConnectionClientImpl>(log = log).withServer<ConnectionServerClientImpl>(log = log)
                this.serverClientTask.set(serverClientTask)
                serverClientTask.registerServer(downloadReqServer)
                serverClientTask.registerServer(errorReqServer)
                serverClientTask.addObserver(closeObserver)
            }

            constructor(
                randomAccessFile: RandomAccessFile,
                downloadReq: DownloadReq,
                connection: ConnectionServerClientImpl) {
                this.randomAccessFile = randomAccessFile
                this.serverClientTask.set(connection)
                this.downloadReq.set(downloadReq)
                connection.addObserver(closeObserver)
                connection.registerServer(errorReqServer)
                connection.registerServer(finishReqServer)
                startSendData(downloadReq)
            }

            fun isActive(): Boolean = serverClientTask.get()?.getCurrentState() is NettyTaskState.ConnectionActive

            fun sendRemoteError(errorMsg: String) {
                if (isActive()) {
                    serverClientTask.get()?.requestSimplify(
                        type = FileTransferDataType.ErrorReq.type,
                        request = ErrorReq(errorMsg),
                        retryTimes = 0,
                        callback = object : IClientManager.RequestCallback<Unit> {
                            override fun onSuccess(
                                type: Int,
                                messageId: Long,
                                localAddress: InetSocketAddress?,
                                remoteAddress: InetSocketAddress?,
                                d: Unit
                            ) {
                                log.d(TAG, "Send error msg success.")
                            }

                            override fun onFail(errorMsg: String) {
                                log.e(TAG, "Send error msg error: $errorMsg")
                            }
                        }
                    )
                }
            }

            fun closeConnectionIfActive() {
                Dispatchers.IO.asExecutor().execute {
                    Thread.sleep(100)
                    serverClientTask.get()?.stopTask()
                    serverClientTask.set(null)
                }
                this@SingleFileFragmentSender.cancel()
            }

            private suspend fun sendDataSuspend(bytes: ByteArray) = suspendCancellableCoroutine { cont ->
                serverClientTask.get()?.requestSimplify(
                    type = FileTransferDataType.SendReq.type,
                    request = bytes,
                    retryTimeout = 4000L,
                    retryTimes = 1,
                    callback = object : IClientManager.RequestCallback<Unit> {
                        override fun onSuccess(
                            type: Int,
                            messageId: Long,
                            localAddress: InetSocketAddress?,
                            remoteAddress: InetSocketAddress?,
                            d: Unit
                        ) {
                            cont.resumeIfActive(d)
                        }

                        override fun onFail(errorMsg: String) {
                            cont.resumeExceptionIfActive(Throwable(errorMsg))
                        }
                    }
                ) ?: cont.resumeExceptionIfActive(Throwable("Task is null."))
            }

            /**
             * Start sending fragment.
             */
            private fun startSendData(downloadReq: DownloadReq) {
                log.d(TAG, "Frame: ${downloadReq.start} started")
                launch {
                    val frameSize = downloadReq.end - downloadReq.start
                    var hasRead = 0L
                    try {
                        val bufferSize = bufferSize.get().toLong()
                        val byteArray = ByteArray(MAX_FILE_SEND_BUFFER_SIZE)
                        while (hasRead < frameSize) {
                            val thisTimeRead = if ((frameSize - hasRead) < bufferSize) {
                                frameSize - hasRead
                            } else {
                                bufferSize
                            }
                            // Read data from file.
                            randomAccessFile.readContent(
                                fileOffset = downloadReq.start + hasRead,
                                byteArray = byteArray,
                                contentLen = thisTimeRead.toInt()
                            )
                            val startTime = System.currentTimeMillis()
                            // Send data to client.
                            sendDataSuspend(byteArray.copyOfRange(0, thisTimeRead.toInt()))
                            val endTime = System.currentTimeMillis()
                            updateBufferSize(endTime - startTime)
                            // Update sending progress.
                            updateProgress(thisTimeRead)
                            hasRead += thisTimeRead
                        }
                        isFragmentFinished.set(true)
                        log.d(TAG, "Frame: ${downloadReq.start} finished($frameSize bytes)")
                    } catch (e: Throwable) {
                        log.e(TAG, "Send file error: ${e.message}", e)
                        errorStateIfActive("Send file error: ${e.message}")
                    }
                }
            }

            private fun updateBufferSize(bufferSendTimeCost: Long) {
                if (bufferSendTimeCost <= 0) {
                    return
                }
                val oldBufferSize = this.bufferSize.get()
                val differCost = bufferSendTimeCost - anchorBufferDurationInMillis
                val durationNeedFix = (oldBufferSize - differCost.toDouble() / anchorBufferDurationInMillis.toDouble() * oldBufferSize.toDouble()).toInt()
                val newBufferSize = max(min(durationNeedFix, MAX_FILE_SEND_BUFFER_SIZE), MIN_FILE_SEND_BUFFER_SIZE)
                this.bufferSize.set(newBufferSize)
            }
        }

    }

    private class UnhandledFragmentRequest(
        val request: DownloadReq,
        val connection: ConnectionServerClientImpl
    )

    companion object {
        private const val TAG = "FileSender"

        private const val MIN_FILE_SEND_BUFFER_SIZE = 512 // 512 Bytes

        private const val DEFAULT_FILE_SEND_BUFFER_SIZE = 1024 * 128 // 128 KB

        private const val MAX_FILE_SEND_BUFFER_SIZE = 1024 * 1024 * 3 // 3 MB
    }
}
