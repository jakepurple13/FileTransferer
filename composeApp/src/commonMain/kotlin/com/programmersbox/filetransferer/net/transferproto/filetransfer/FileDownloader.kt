package com.programmersbox.filetransferer.net.transferproto.filetransfer

import com.programmersbox.filetransferer.net.ILog
import com.programmersbox.filetransferer.net.netty.INettyConnectionTask
import com.programmersbox.filetransferer.net.netty.NettyConnectionObserver
import com.programmersbox.filetransferer.net.netty.NettyTaskState
import com.programmersbox.filetransferer.net.netty.PackageData
import com.programmersbox.filetransferer.net.netty.extensions.*
import com.programmersbox.filetransferer.net.netty.tcp.NettyTcpClientConnectionTask
import com.programmersbox.filetransferer.net.resumeExceptionIfActive
import com.programmersbox.filetransferer.net.resumeIfActive
import com.programmersbox.filetransferer.net.transferproto.SimpleCallback
import com.programmersbox.filetransferer.net.transferproto.SimpleObservable
import com.programmersbox.filetransferer.net.transferproto.SimpleStateable
import com.programmersbox.filetransferer.net.transferproto.TransferProtoConstant
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.DownloadReq
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.ErrorReq
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.FileTransferDataType
import com.programmersbox.filetransferer.net.writeContent
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FileDownloader could download multiple files one time, single file downloads see [SingleFileDownloader], single file contains multiple fragments,
 * one fragment uses a TCP connection to downloads file's fragment see [SingleFileDownloader.SingleFileFragmentDownloader]
 */
class FileDownloader(
    val downloadDir: File,
    val files: List<FileExploreFile>,
    val connectAddress: InetAddress,
    val maxConnectionSize: Long,
    val minFrameSize: Long = 1024 * 1024 * 10, // 10MB
    val log: ILog,
    private val contactsImporter: suspend (File) -> Unit = {},
) : SimpleObservable<FileTransferObserver>, SimpleStateable<FileTransferState> {

    override val observers: LinkedBlockingDeque<FileTransferObserver> = LinkedBlockingDeque()

    override val state: AtomicReference<FileTransferState> = AtomicReference(FileTransferState.NotExecute)

    /**
     * Waiting download file tasks.
     */
    private val waitingDownloader: LinkedBlockingDeque<SingleFileDownloader> = LinkedBlockingDeque()

    /**
     * Downloading file task.
     */
    private val workingDownloader: AtomicReference<SingleFileDownloader?> = AtomicReference(null)

    /**
     * Finished file download tasks.
     */
    private val finishedDownloader: LinkedBlockingDeque<SingleFileDownloader> = LinkedBlockingDeque()


    override fun addObserver(o: FileTransferObserver) {
        super.addObserver(o)
        o.onNewState(state.get())
    }

    /**
     * Start download files, downloader as client.
     */
    @Synchronized
    fun start() {
        if (getCurrentState() is FileTransferState.NotExecute) {
            newState(FileTransferState.Started)
            waitingDownloader.clear()
            // Single file download handle by SingleFileDownloader
            for (f in files) {
                waitingDownloader.add(SingleFileDownloader(f))
            }
            // Move first file download task to downloading task.
            doNextDownloader(null)
        }
    }

    @Synchronized
    fun cancel() {
        if (getCurrentState() !is FileTransferState.NotExecute) {
            assertActive {
                newState(FileTransferState.Canceled)
            }
            closeConnectionIfActive()
            closeConnectionAndReportError("User canceled")
        }
    }

    private fun doNextDownloader(finishedDownloader: SingleFileDownloader?) {
        assertActive {
            if (finishedDownloader != null) {
                this.finishedDownloader.add(finishedDownloader)
                // Notify observers single file download finished.
                for (o in observers) {
                    o.onEndFile(finishedDownloader.file)
                }
            }
            // Move waiting task to working.
            val targetDownloader = waitingDownloader.pollFirst()
            if (targetDownloader != null) {
                targetDownloader.onActive(finishedDownloader)
                workingDownloader.set(targetDownloader)
                // Notify observers single file download start.
                for (o in observers) {
                    o.onStartFile(targetDownloader.file)
                }
            } else {
                // No waiting file task, all files download finished.
                newState(FileTransferState.Finished)
                finishedDownloader?.closeFragmentsConnection()
                closeConnectionIfActive()
            }
        }
    }

    private fun errorStateIfActive(errorMsg: String) {
        assertActive {
            newState(FileTransferState.Error(errorMsg))
        }
        closeConnectionAndReportError(errorMsg)
    }

    private fun remoteErrorStateIfActive(errorMsg: String) {
        assertActive {
            newState(FileTransferState.RemoteError(errorMsg))
        }
        closeConnectionIfActive()
    }

    private fun dispatchProgressUpdate(hasDownloadedSize: Long, file: FileExploreFile) {
        assertActive {
            for (o in observers) {
                o.onProgressUpdate(file, hasDownloadedSize)
            }
        }
    }

    private fun assertActive(notActive: (() -> Unit)? = null, active: () -> Unit) {
        if (getCurrentState() == FileTransferState.Started) {
            active()
        } else {
            notActive?.invoke()
        }
    }

    private fun closeConnectionIfActive() {
        workingDownloader.get()?.onCanceled("none", false)
        workingDownloader.set(null)
        waitingDownloader.clear()
    }

    private fun closeConnectionAndReportError(errorMsg: String) {
        workingDownloader.get()?.onCanceled(errorMsg, true)
        workingDownloader.set(null)
        waitingDownloader.clear()
    }

    override fun onNewState(s: FileTransferState) {
        super.onNewState(s)
        for (o in observers) {
            o.onNewState(s)
        }
    }

    private inner class SingleFileDownloader(val file: FileExploreFile) : CoroutineScope by CoroutineScope(Dispatchers.IO) {

        private val singleFileHasDownloadSize: AtomicLong by lazy {
            AtomicLong(0L)
        }

        private val isSingleFileDownloaderExecuted: AtomicBoolean by lazy {
            AtomicBoolean(false)
        }

        private val isSingleFileDownloaderCanceled: AtomicBoolean by lazy {
            AtomicBoolean(false)
        }

        private val isSingleFileFinished: AtomicBoolean by lazy {
            AtomicBoolean(false)
        }

        private val downloadingFile: AtomicReference<File?> by lazy {
            AtomicReference(null)
        }

        private val fragmentDownloader: LinkedBlockingDeque<SingleFileFragmentDownloader> by lazy {
            LinkedBlockingDeque()
        }


        private val randomAccessFile: AtomicReference<RandomAccessFile?> by lazy {
            AtomicReference(null)
        }


        /**
         * SingleFileDownloader move to working task from waiting tasks.
         */
        fun onActive(lastSingleFileDownloader: SingleFileDownloader?) {
            if (!isSingleFileDownloaderCanceled.get() && !isSingleFileFinished.get() && isSingleFileDownloaderExecuted.compareAndSet(false, true)) {
                try {
                    println("Downloading file!")
                    // Create downloading file.
                    val downloadingFile = createDownloadingFile()
                    this.downloadingFile.set(downloadingFile)
                    val randomAccessFile = RandomAccessFile(downloadingFile, "rw")
                    this.randomAccessFile.get()?.close()
                    this.randomAccessFile.set(randomAccessFile)
                    randomAccessFile.setLength(file.size)
                    // Compute every fragment size.
                    val fragmentsRange = createFragmentsRange()
                    log.d(TAG, "Real download fragment size: ${fragmentsRange.size}")
                    launch {
                        // Create SingleFileFragmentDownloaders base on fragments.
                        for ((start, end) in fragmentsRange) {
                            // Fragment connection retry 3 times.
                            var tryTimes = 3
                            var result: Result<SingleFileFragmentDownloader>
                            do {
                                val fd = SingleFileFragmentDownloader(
                                    randomAccessFile = randomAccessFile,
                                    start = start,
                                    end = end
                                )
                                result = runCatching {
                                    // Start fragment connection, connect to server.
                                    fd.connectToServerSuspend()
                                    fd
                                }
                                if (result.isSuccess) {
                                    break
                                }

                                delay(200L)
                            } while (--tryTimes > 0)
                            val fd = result.getOrNull()
                            if (fd != null) {
                                fragmentDownloader.add(fd)
                                delay(200)
                            } else {
                                val msg = "Connect error: ${result.exceptionOrNull()?.message}"
                                log.e(TAG, msg, result.exceptionOrNull())
                                errorStateIfActive(msg)
                                break
                            }
                        }
                        lastSingleFileDownloader?.closeFragmentsConnection()
                    }
                } catch (e: Throwable) {
                    val msg = "Create download task fail: ${e.message}"
                    log.e(TAG, msg, e)
                    lastSingleFileDownloader?.closeFragmentsConnection()
                    errorStateIfActive(msg)
                }
            }
        }

        fun onCanceled(reason: String, reportRemote: Boolean) {
            if (isSingleFileDownloaderExecuted.get() && !isSingleFileFinished.get() && isSingleFileDownloaderCanceled.compareAndSet(false, true)) {
                if (reportRemote) {
                    for (f in fragmentDownloader) {
                        f.sendRemoteError(reason)
                    }
                }
                closeFragmentsConnection()
                try {
                    recycleResource()
                    downloadingFile.get()?.let {
                        it.delete()
                        downloadingFile.set(null)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

        fun closeFragmentsConnection() {
            for (f in fragmentDownloader) {
                f.closeConnectionIfActive()
            }
            fragmentDownloader.clear()
        }

        private fun updateProgress(downloadedSize: Long) {
            val hasDownloadedSize = singleFileHasDownloadSize.addAndGet(downloadedSize)
            // Notify progress to observers.
            dispatchProgressUpdate(hasDownloadedSize, file)
            if (hasDownloadedSize >= file.size) {
                // Single file download finished.
                onFinished()
            }
        }

        private fun onFinished() {
            if (isSingleFileDownloaderExecuted.get() && !isSingleFileDownloaderCanceled.get() && isSingleFileFinished.compareAndSet(false, true)) {
                try {
                    recycleResource()
                    // Rename downloaded file's name.
                    downloadingFile.get()?.let {
                        val f = getDownloadedFile(file.name)
                        it.renameTo(f)
                        /*runBlocking {
                            println(file)
                            if (f.name.endsWith(".vcf")) {
                                println("Starting import")
                                contactsImporter(f)
                            }
                        }*/
                        downloadingFile.set(null)
                        log.d(TAG, "File: ${file.name} download success!!!")
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
                // Start next SingleFileDownloader.
                doNextDownloader(this)
            }
        }

        private fun recycleResource() {
            try {
                randomAccessFile.get()?.let {
                    it.close()
                    randomAccessFile.set(null)
                }
                this.cancel()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        private fun createFragmentsRange(): List<Pair<Long, Long>> {
            val fileSize = file.size
            val frameCount = maxConnectionSize
            val minFrameSize = minFrameSize
            if (fileSize < 0) error("Wrong file size: $fileSize")
            if (minFrameSize <= 0) error("Wrong min frame size: $minFrameSize")
            if (frameCount <= 0) error("Wrong min frame count: $frameCount")
            return if (frameCount * minFrameSize > fileSize) {
                val lastFrameSize = fileSize % minFrameSize
                val realFrameCount = fileSize / minFrameSize + if (lastFrameSize > 0L) 1 else 0
                val result = mutableListOf<Pair<Long, Long>>()
                for (i in 0 until realFrameCount) {
                    val start = i * minFrameSize
                    val end = if (i != realFrameCount - 1) (i + 1) * minFrameSize else fileSize
                    result.add(start to end)
                }
                result
            } else {
                val lastFrameSize = fileSize % frameCount
                val frameSize = if (lastFrameSize == 0L) {
                    fileSize / frameCount
                }  else {
                    (fileSize - lastFrameSize) / (frameCount - 1)
                }
                val result = mutableListOf<Pair<Long, Long>>()
                for (i in 0 until frameCount) {
                    val start = i * frameSize
                    val end = if (i != frameCount - 1) (i + 1) * frameSize else fileSize
                    result.add(start to end)
                }
                result
            }
        }

        private fun createDownloadingFile(): File {
            if (!downloadDir.isDirectory) {
                downloadDir.mkdirs()
            }
            val downloadingFileName = "${file.name}.downloading"
            val downloadingFile = File(downloadDir, downloadingFileName)
            if (downloadingFile.isFile) {
                downloadingFile.delete()
            }
            downloadingFile.createNewFile()
            return downloadingFile
        }

        /**
         * return file is not exists
         */
        private fun getDownloadedFile(fileName: String): File {
            if (!downloadDir.isDirectory) {
                downloadDir.mkdirs()
            }
            val targetFile = File(downloadDir, fileName)
            if (!targetFile.exists()) {
                return targetFile
            }
            val nameIndexSuffixRegex = "((.|\\s)+)-(\\d+)(\\.(.|\\s)+)$".toRegex()
            val nameSuffix = "((.|\\s)+)(\\.(.|\\s)+)\$".toRegex()
            val nameIndex = "((.|\\s)+)-(\\d+)$".toRegex()
            return when {
                nameIndexSuffixRegex.matches(fileName) -> {
                    val values = nameIndexSuffixRegex.find(fileName)?.groupValues ?: emptyList()
                    val name = values.getOrNull(1) ?: ""
                    val index = values.getOrNull(3) ?: "0"
                    val suffix = values.getOrNull(4) ?: ""
                    getDownloadedFile("$name-${index.toLong() + 1}$suffix")
                }
                nameSuffix.matches(fileName) -> {
                    val values = nameSuffix.find(fileName)?.groupValues ?: emptyList()
                    val name = values.getOrNull(1) ?: ""
                    val suffix = values.getOrNull(3) ?: ""
                    getDownloadedFile("$name-1$suffix")
                }
                nameIndex.matches(fileName) -> {
                    val values = nameIndexSuffixRegex.find(fileName)?.groupValues ?: emptyList()
                    val name = values.getOrNull(1) ?: ""
                    val index = values.getOrNull(2) ?: "0"
                    getDownloadedFile("$name-${index.toLong() + 1}")
                }
                else -> {
                    getDownloadedFile("$fileName-1")
                }
            }
        }

        private suspend fun SingleFileFragmentDownloader.connectToServerSuspend(): Unit = suspendCancellableCoroutine { cont ->
            this.connectToServer(simpleCallback = object : SimpleCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    cont.resumeIfActive(Unit)
                }

                override fun onError(errorMsg: String) {
                    cont.resumeExceptionIfActive(Throwable(errorMsg))
                }
            })
        }

        private inner class SingleFileFragmentDownloader(
            private val randomAccessFile: RandomAccessFile,
            private val start: Long,
            private val end: Long
        ) {

            private val size: Long = end - start

            private val task: AtomicReference<ConnectionServerClientImpl?> by lazy {
                AtomicReference(null)
            }

            private val isFragmentDownloaderFinished: AtomicBoolean by lazy {
                AtomicBoolean(false)
            }

            private val isFragmentDownloaderClosed: AtomicBoolean by lazy {
                AtomicBoolean(false)
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

            private val downloadedSize = AtomicLong(0)

            private val receiveDataServer: IServer<ByteArray, Unit> by lazy {
                simplifyServer(
                    requestType = FileTransferDataType.SendReq.type,
                    responseType = FileTransferDataType.SendResp.type,
                    log = log,
                    onRequest = { _, _, data, isNew ->
                        // File's data coming from FileSender.
                        if (isNew) {
                            if (!isFragmentDownloaderClosed.get() && !isFragmentDownloaderFinished.get()) {
                                try {
                                    if (downloadedSize.get() == 0L) {
                                        log.d(TAG, "Frame: $start download started.")
                                    }
                                    // Write data to real download file.
                                    randomAccessFile.writeContent(
                                        fileOffset = downloadedSize.get() + start,
                                        byteArray = data,
                                        contentLen = data.size
                                    )
                                    if (downloadedSize.addAndGet(data.size.toLong()) >= size) {
                                        // Fragment download finished.
                                        log.d(TAG, "Frame: $start download finished(${end - start} bytes).")
                                        isFragmentDownloaderFinished.set(true)
                                        val t = task.get()
                                        if (t != null) {
                                            // Notify server this fragment download finished, connection need close.
                                            t.requestSimplify(
                                                type = FileTransferDataType.FinishedReq.type,
                                                request = Unit,
                                                retryTimes = 0,
                                                retryTimeout = 2500,
                                                callback = object : IClientManager.RequestCallback<Unit> {
                                                    override fun onSuccess(
                                                        type: Int,
                                                        messageId: Long,
                                                        localAddress: InetSocketAddress?,
                                                        remoteAddress: InetSocketAddress?,
                                                        d: Unit
                                                    ) {
                                                        log.d(TAG, "Send fragment finish success.")
                                                        updateProgress(data.size.toLong())
                                                        closeConnectionIfActive()
                                                    }

                                                    override fun onFail(errorMsg: String) {
                                                        updateProgress(data.size.toLong())
                                                        log.e(TAG, "Send fragment finish fail: $errorMsg")
                                                        // closeConnectionIfActive()
                                                    }

                                                }
                                            )
                                        } else {
                                            updateProgress(data.size.toLong())
                                            errorStateIfActive("Task is null")
                                        }
                                    } else {
                                        // Update download progress.
                                        updateProgress(data.size.toLong())
                                    }
                                } catch (e: Throwable) {
                                    val msg = "Write data error: ${e.message}"
                                    log.e(TAG, msg)
                                    errorStateIfActive(msg)
                                }
                            }
                        }
                    }
                )
            }

            private val closeObserver: NettyConnectionObserver by lazy {
                object : NettyConnectionObserver {
                    override fun onNewState(
                        nettyState: NettyTaskState,
                        task: INettyConnectionTask
                    ) {
                        if (nettyState is NettyTaskState.ConnectionClosed ||
                                nettyState is NettyTaskState.Error) {
                            if (!isFragmentDownloaderClosed.get() && !isFragmentDownloaderFinished.get()) {
                                val msg = "Fragment downloader is closed: $nettyState"
                                log.e(TAG, msg)
                                errorStateIfActive(msg)
                            }
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

            fun connectToServer(simpleCallback: SimpleCallback<Unit>) {
                connectToServerWithRetry(simpleCallback, 1, 1000)
            }

            private fun connectToServerWithRetry(simpleCallback: SimpleCallback<Unit>, attempt: Int, delayMs: Long) {
                // Fragment's connection task
                val task = NettyTcpClientConnectionTask(
                    serverAddress = connectAddress,
                    serverPort = TransferProtoConstant.FILE_TRANSFER_PORT
                ).withServer<ConnectionServerImpl>(log = log)
                    .withClient<ConnectionServerClientImpl>(log = log)
                this.task.set(task)
                task.addObserver(object : NettyConnectionObserver {
                    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
                    override fun onNewState(
                        nettyState: NettyTaskState,
                        localTask: INettyConnectionTask
                    ) {
                        if (nettyState is NettyTaskState.ConnectionActive) {
                            // Connection success.
                            simpleCallback.onSuccess(Unit)
                            task.removeObserver(this)
                            task.addObserver(closeObserver)
                            task.registerServer(errorReqServer)
                            task.registerServer(receiveDataServer)
                            // Send fragment's information to server.
                            sendDownloadRequest(task)
                        }
                        if (nettyState is NettyTaskState.ConnectionClosed
                            || nettyState is NettyTaskState.Error) {
                            // Connection fail.
                            val maxRetries = 5
                            if (attempt < maxRetries) {
                                // Retry with exponential backoff
                                log.d(TAG, "Connection attempt $attempt failed, retrying in ${delayMs}ms...")
                                task.removeObserver(this)
                                task.stopTask()

                                // Schedule retry with exponential backoff
                                Dispatchers.IO.asExecutor().execute {
                                    Thread.sleep(delayMs)
                                    connectToServerWithRetry(simpleCallback, attempt + 1, delayMs * 2)
                                }
                            } else {
                                // Max retries reached, report error
                                log.e(TAG, "Connection failed after $maxRetries attempts")
                                (nettyState as? NettyTaskState.Error)?.throwable?.printStackTrace()
                                simpleCallback.onError("Connect error: $nettyState, throwable: ${(nettyState as? NettyTaskState.Error)?.throwable}")
                                task.removeObserver(this)
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
                task.startTask()
            }

            fun isActive(): Boolean = task.get()?.getCurrentState() is NettyTaskState.ConnectionActive

            fun sendRemoteError(errorMsg: String) {
                if (isActive()) {
                    task.get()?.requestSimplify(
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
                    Thread.sleep(500)
                    task.get()?.let {
                        it.stopTask()
                        task.set(null)
                    }
                }
                isFragmentDownloaderClosed.set(true)
            }

            private fun sendDownloadRequest(task: ConnectionServerClientImpl) {
                task.requestSimplify(
                    type = FileTransferDataType.DownloadReq.type,
                    request = DownloadReq(
                        file = file,
                        start = start, // Fragment's start.
                        end = end // Fragment's end.
                    ),
                    callback = object : IClientManager.RequestCallback<Unit> {
                        override fun onSuccess(
                            type: Int,
                            messageId: Long,
                            localAddress: InetSocketAddress?,
                            remoteAddress: InetSocketAddress?,
                            d: Unit
                        ) {}

                        override fun onFail(errorMsg: String) {
                            val msg = "Download req fail: $errorMsg"
                            log.e(TAG, msg)
                            errorStateIfActive(msg)
                        }
                    }
                )
            }
        }

    }

    companion object {
        private const val TAG = "FileDownloader"
    }

}
