package com.programmersbox.filetransferer

import android.os.Build
import android.os.Environment
import java.io.File

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()
actual fun getDefaultDownloadDir(): String {
    return File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "fileTransferer"
    ).canonicalPath
}