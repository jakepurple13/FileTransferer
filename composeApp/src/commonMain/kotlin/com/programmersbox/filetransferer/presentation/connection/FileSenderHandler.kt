package com.programmersbox.filetransferer.presentation.connection

import com.programmersbox.filetransferer.DefaultLogger
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import com.programmersbox.filetransferer.net.transferproto.filetransfer.FileSender
import com.programmersbox.filetransferer.net.transferproto.filetransfer.FileTransferObserver
import com.programmersbox.filetransferer.net.transferproto.filetransfer.FileTransferState
import com.programmersbox.filetransferer.net.transferproto.filetransfer.SpeedCalculator
import com.programmersbox.filetransferer.net.transferproto.filetransfer.model.SenderFile
import java.net.InetAddress
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.getValue

class FileSenderHandler(
    private val bindAddress: InetAddress,
    private val files: List<SenderFile>,
    private val updateState: ((FileTransferDialogState) -> FileTransferDialogState) -> Unit,
    private val onResult: (FileTransferResult) -> Unit
) {

    private val sender: AtomicReference<FileSender?> by lazy {
        AtomicReference(null)
    }

    private val speedCalculator: AtomicReference<SpeedCalculator?> by lazy {
        AtomicReference(null)
    }

    suspend fun send() {
        val sender = FileSender(
            files = files,
            bindAddress = bindAddress,
            log = DefaultLogger,
        )
        this.sender.get()?.cancel()
        this.sender.set(sender)
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
        sender.addObserver(
            object : FileTransferObserver {
                override fun onNewState(s: FileTransferState) {
                    when (s) {
                        FileTransferState.NotExecute -> {}
                        FileTransferState.Started -> {
                            speedCalculator.start()
                        }

                        FileTransferState.Canceled -> {
                            onResult(FileTransferResult.Cancel)
                        }

                        FileTransferState.Finished -> {
                            speedCalculator.stop()
                            onResult(FileTransferResult.Finished)
                        }

                        is FileTransferState.Error -> {
                            speedCalculator.stop()
                            onResult(FileTransferResult.Error(s.msg))
                        }

                        is FileTransferState.RemoteError -> {
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
        sender.start()
    }
}