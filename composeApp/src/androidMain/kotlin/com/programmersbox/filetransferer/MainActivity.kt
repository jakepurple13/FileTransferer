package com.programmersbox.filetransferer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.tooling.preview.Preview
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        val permissionsNeed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsNeed.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissionsNeed.add(Manifest.permission.READ_MEDIA_AUDIO)
                permissionsNeed.add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    permissionsNeed.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else {
                permissionsNeed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            permissionsNeed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsNeed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            permissionsNeed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }
            SideEffect { permissionLauncher.launch(permissionsNeed.toTypedArray()) }

            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}