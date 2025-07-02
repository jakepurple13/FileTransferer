package com.programmersbox.filetransferer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.filetransferer.connection.showQRCodeServerDialog
import com.programmersbox.filetransferer.net.netty.findLocalAddressV4
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExplore
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExploreRequestHandler
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.DownloadFilesReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.DownloadFilesResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.ScanDirReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.ScanDirResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendFilesReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendFilesResp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview

import filetransferer.composeapp.generated.resources.Res
import filetransferer.composeapp.generated.resources.compose_multiplatform
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.qrcode.CameraPosition
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions

@Composable
@Preview
fun App() {
    val f = remember {
        FileExplore(
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
                    return null
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
    }
    MaterialTheme(
        colorScheme = darkColorScheme()
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
                        println(scan)

                        false
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
                    requestTransferFile = { },
                    cancelRequest = { }
                )
            }
        }
    }
}