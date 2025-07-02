package com.programmersbox.filetransferer

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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.programmersbox.filetransferer.presentation.qrcode.showQRCodeServerDialog
import com.programmersbox.filetransferer.net.netty.findLocalAddressV4
import com.programmersbox.filetransferer.net.netty.toInetAddress
import com.programmersbox.filetransferer.net.netty.toInt
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExplore
import com.programmersbox.filetransferer.net.transferproto.fileexplore.FileExploreRequestHandler
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.DownloadFilesReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.DownloadFilesResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.ScanDirReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.ScanDirResp
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendFilesReq
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendFilesResp
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.QRCodeScanClient
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.requestFileTransferSuspend
import com.programmersbox.filetransferer.net.transferproto.qrscanconn.startQRCodeScanClientSuspend
import com.programmersbox.filetransferer.presentation.ConnectionScreen
import com.programmersbox.filetransferer.presentation.Home
import com.programmersbox.filetransferer.presentation.ScanQrCodeScreen
import com.programmersbox.filetransferer.presentation.connection.ConnectionScreen
import com.programmersbox.filetransferer.presentation.qrcode.ScanQrCodeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.ui.tooling.preview.Preview

import org.publicvalue.multiplatform.qrcode.CameraPosition
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions

val LocalNavController = compositionLocalOf<NavHostController>{ error("No!") }

@Composable
@Preview
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        val navController = rememberNavController()
        CompositionLocalProvider(LocalNavController provides navController) {
            NavHost(
                navController = navController,
                startDestination = ScanQrCodeScreen
            ) {
                composable<Home> {

                }

                composable<ScanQrCodeScreen> {
                    val scope = rememberCoroutineScope()
                    ScanQrCodeScreen(
                        onQrCodeScan = { qrcodeShare ->
                            scope.launch {
                                runCatching {
                                    val scanClient = QRCodeScanClient(DefaultLogger)
                                    val serverAddress = qrcodeShare.address.toInetAddress()
                                    // Create request transfer file to QRCodeServer connection.
                                    scanClient.startQRCodeScanClientSuspend(serverAddress)
                                    withContext(Dispatchers.IO) {
                                        // Request transfer file.
                                        scanClient.requestFileTransferSuspend(
                                            targetAddress = serverAddress,
                                            deviceName = "DEVICE"
                                        )
                                    }
                                    serverAddress
                                }.onSuccess { serverAddress ->
                                    withContext(Dispatchers.Main) {
                                        navController.navigate(
                                            ConnectionScreen(
                                                remoteAddress = serverAddress.toInt(),
                                                isServer = false,
                                                localAddress = findLocalAddressV4().first().toInt()
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onRemoteConnected = { remoteDevice ->
                            scope.launch(Dispatchers.Main) {
                                navController.navigate(
                                    ConnectionScreen(
                                        remoteAddress = remoteDevice.remoteAddress.address.toInt(),
                                        isServer = true,
                                        localAddress = findLocalAddressV4().first().toInt()
                                    )
                                )
                            }
                        }
                    )
                }

                composable<ConnectionScreen> {
                    ConnectionScreen()
                }
            }
        }
    }
}