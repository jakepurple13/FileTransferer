package com.programmersbox.filetransferer.presentation.connection

import com.programmersbox.filetransferer.DefaultLogger
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import com.programmersbox.filetransferer.net.transferproto.filetransfer.FileDownloader
import com.programmersbox.filetransferer.net.transferproto.filetransfer.FileTransferObserver
import com.programmersbox.filetransferer.net.transferproto.filetransfer.FileTransferState
import com.programmersbox.filetransferer.net.transferproto.filetransfer.SpeedCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.net.InetAddress
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

class FileDownloadHandler(
    private val senderAddress: InetAddress,
    private val files: List<FileExploreFile>,
    private val downloadDir: File,
    private val maxConnectionSize: Int,
    private val updateState: ((FileTransferDialogState) -> FileTransferDialogState) -> Unit,
    private val onResult: (FileTransferResult) -> Unit
) {

    private val downloader: AtomicReference<FileDownloader?> by lazy {
        AtomicReference(null)
    }

    private val speedCalculator: AtomicReference<SpeedCalculator?> by lazy {
        AtomicReference(null)
    }

    fun download() {
        val downloader = FileDownloader(
            files = files,
            downloadDir = downloadDir,
            connectAddress = senderAddress,
            maxConnectionSize = maxConnectionSize.toLong(),
            log = DefaultLogger,
            contactsImporter = {
                println("Starting to import $it")
                /*vcfImporter.importContacts(
                    contacts = contacts,
                    path = it
                )*/
            }
        )
        this.downloader.get()?.cancel()
        this.downloader.set(downloader)
        val speedCalculator = SpeedCalculator()
        speedCalculator.addObserver(
            object : SpeedCalculator.Companion.SpeedObserver {
                override fun onSpeedUpdated(speedInBytes: Long, speedInString: String) {
                    updateState {
                        it.copy(speedString = speedInString)
                    }
                }
            }
        )
        this.speedCalculator.set(speedCalculator)
        fun checkFinishedFileAndInsertToMediaStore() {
            //val finishedFiles = this@FileDownloaderDialog.currentState().finishedFiles
            /*if (finishedFiles.isNotEmpty()) {
                Dispatchers.IO.asExecutor().execute {
                    val pathAndMimeType = finishedFiles.mapNotNull {
                        val mimeType = getMediaMimeTypeWithFileName(it.name)?.first
                        if (mimeType != null) {
                            File(downloadDir, it.name).canonicalPath to mimeType
                        } else {
                            null
                        }
                    }
                    if (pathAndMimeType.isNotEmpty()) {
                        MediaScannerConnection.scanFile(
                            ctx,
                            pathAndMimeType.map { it.first }.toTypedArray(),
                            pathAndMimeType.map { it.second }.toTypedArray(), null
                        )
                    }
                }
            }*/
        }
        downloader.addObserver(
            object : FileTransferObserver {
                override fun onNewState(s: FileTransferState) {
                    when (s) {
                        FileTransferState.NotExecute -> {
                            println("NotExecute")
                        }
                        FileTransferState.Started -> {
                            speedCalculator.start()
                            println("Started")
                        }

                        FileTransferState.Canceled -> {
                            checkFinishedFileAndInsertToMediaStore()
                            speedCalculator.stop()
                            onResult(FileTransferResult.Cancel)
                        }

                        FileTransferState.Finished -> {
                            checkFinishedFileAndInsertToMediaStore()
                            speedCalculator.stop()
                            onResult(FileTransferResult.Finished)
                        }

                        is FileTransferState.Error -> {
                            checkFinishedFileAndInsertToMediaStore()
                            speedCalculator.stop()
                            onResult(FileTransferResult.Error(s.msg))
                        }

                        is FileTransferState.RemoteError -> {
                            checkFinishedFileAndInsertToMediaStore()
                            speedCalculator.stop()
                            onResult(FileTransferResult.Error("Remote error: ${s.msg}"))
                        }
                    }
                }

                override fun onStartFile(file: FileExploreFile) {
                    speedCalculator.reset()
                    updateState {
                        it.copy(
                            transferFile = Optional.of(file),
                            process = 0L,
                            speedString = ""
                        )
                    }
                }

                override fun onProgressUpdate(file: FileExploreFile, progress: Long) {
                    speedCalculator.updateCurrentSize(progress)
                    updateState { oldState ->
                        oldState.copy(process = progress)
                    }
                }

                override fun onEndFile(file: FileExploreFile) {
                    updateState { oldState -> oldState.copy(finishedFiles = oldState.finishedFiles + file) }
                }

            }
        )
        downloader.start()
    }
}