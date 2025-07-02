package com.programmersbox.filetransferer.presentation.qrcode

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.filetransferer.net.netty.findLocalAddressV4
import com.programmersbox.filetransferer.net.transferproto.broadcastconn.model.RemoteDevice
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanState
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.model.QRCodeShare
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.qrcode.CameraPosition
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions

@Composable
fun ScanQrCodeScreen(
    onQrCodeScan: (QRCodeShare) -> Unit,
    onRemoteConnected: (RemoteDevice) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        var showQRCode by remember { mutableStateOf(false) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            ScannerWithPermissions(
                onScanned = { scan ->
                    onQrCodeScan(Json.decodeFromString(scan))
                    true
                },
                types = listOf(CodeType.QR),
                cameraPosition = CameraPosition.BACK,
                permissionDeniedContent = { permissionState ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .sizeIn(maxWidth = 250.dp, maxHeight = 250.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface,
                                MaterialTheme.shapes.medium
                            )
                    ) {
                        Text(
                            text = "Camera is required for QR Code scanning",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(6.dp)
                        )
                        ElevatedButton(
                            onClick = { permissionState.goToSettings() }
                        ) { Text("Open Settings") }
                    }
                },
                modifier = Modifier
                    .sizeIn(maxWidth = 250.dp, maxHeight = 250.dp)
                    .clip(MaterialTheme.shapes.medium)
            )

            TextButton(
                onClick = { showQRCode = true }
            ) { Text("Show QR Code") }
        }

        if (showQRCode) {
            showQRCodeServerDialog(
                localAddress = remember { findLocalAddressV4().first() },
                localDeviceInfo = "Device",
                requestTransferFile = { onRemoteConnected(it) },
                cancelRequest = { showQRCode = false }
            )
        }
    }
}