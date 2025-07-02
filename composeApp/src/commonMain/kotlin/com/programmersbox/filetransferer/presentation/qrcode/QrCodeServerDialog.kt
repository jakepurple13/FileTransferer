package com.programmersbox.filetransferer.presentation.qrcode

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.programmersbox.filetransferer.DefaultLogger
import com.programmersbox.filetransferer.net.netty.toInt
import com.programmersbox.filetransferer.net.transferproto.TransferProtoConstant
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanServer
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanServerObserver
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanState
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.model.QRCodeShare
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.startQRCodeScanServerSuspend
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.Optional

@Composable
fun showQRCodeServerDialog(
    localAddress: InetAddress,
    localDeviceInfo: String,
    requestTransferFile: (remoteDevice: RemoteDevice) -> Unit,
    cancelRequest: () -> Unit
) {
    val painter = rememberQrCodePainter(
        Json.encodeToString(
            QRCodeShare(
                version = TransferProtoConstant.VERSION,
                deviceName = localDeviceInfo,
                address = localAddress.toInt()
            )
        )
    ) {

    }
    val scope = rememberCoroutineScope()
    val d = remember {
        QRCodeServerDialog(
            localAddress,
            localDeviceInfo,
            requestTransferFile,
            painter,
            cancelRequest
        )
    }
    DisposableEffect(Unit) {
        scope.launch { d.initData() }
        onDispose { d.stop() }
    }
    //d.start()
    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Card(
                modifier = Modifier.width(350.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 15.dp,
                            top = 17.dp,
                            end = 15.dp,
                            bottom = 5.dp
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.onSurface,
                                    MaterialTheme.shapes.medium
                                )
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .padding(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { d.stop() }
                            ) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

class QRCodeServerDialog(
    private val localAddress: InetAddress,
    private val localDeviceInfo: String,
    private val requestTransferFile: (remoteDevice: RemoteDevice) -> Unit,
    private val painter: Painter,
    private val cancelRequest: () -> Unit
) {

    var uiState by mutableStateOf(QRCodeServerState())

    private val qrcodeServer: QRCodeScanServer by lazy {
        QRCodeScanServer(log = DefaultLogger)
    }

    suspend fun initData() {
        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            qrcodeServer.addObserver(
                object : QRCodeScanServerObserver {
                    override fun requestTransferFile(remoteDevice: RemoteDevice) {
                        DefaultLogger.d(TAG, "Receive request: $remoteDevice")
                        this@QRCodeServerDialog.requestTransferFile(remoteDevice)
                        cancel()
                    }

                    override fun onNewState(state: QRCodeScanState) {
                        DefaultLogger.d(TAG, "Qrcode server state: $state")
                    }
                }
            )
            runCatching {
                qrcodeServer.startQRCodeScanServerSuspend(localAddress = localAddress)
            }.onSuccess {
                DefaultLogger.d(TAG, "Bind address success.")
                runCatching {
                    val qrcodeContent = Json.encodeToString(
                        QRCodeShare(
                            version = TransferProtoConstant.VERSION,
                            deviceName = localDeviceInfo,
                            address = localAddress.toInt()
                        )
                    )
                    /*val qrCodeWriter = QRCodeWriter()
                    val matrix = qrCodeWriter.encode(qrcodeContent, BarcodeFormat.QR_CODE, 320, 320)
                    val bufferedImage = MatrixToImageWriter.toBufferedImage(matrix)
                    bufferedImage.toPainter()*/
                    painter
                }.onSuccess {
                    uiState = uiState.copy(qrcodePainter = Optional.of(it))
                }.onFailure {
                    val ss = it.stackTrace
                    val stringBuilder = StringBuilder()
                    for (s in ss) {
                        stringBuilder.appendLine(s.toString())
                    }
                    val eMsg = "Create qrcode fail: ${it.message} \n" + stringBuilder.toString()
                    DefaultLogger.e(TAG, eMsg, it)
                    cancel()
                }
            }.onFailure {
                val eMsg = "Bind address: $localAddress fail"
                DefaultLogger.e(TAG, eMsg)
                cancel()
            }
        }
    }

    fun stop() {
        Dispatchers.IO.asExecutor().execute {
            Thread.sleep(1000)
            cancelRequest()
            qrcodeServer.closeConnectionIfActive()
        }
    }

    companion object {
        private const val TAG = "QRCodeServerDialog"

        data class QRCodeServerState(
            val qrcodePainter: Optional<Painter> = Optional.empty(),
        )
    }
}