package com.programmersbox.filetransferer

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.context
import io.github.vinceglb.filekit.dialogs.uri
import io.github.vinceglb.filekit.path
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


actual fun readPlatformFile(file: PlatformFile): PlatformFile {
    val context = FileKit.context
    val d = context.contentResolver.let {
        it.query(file.uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            cursor.moveToFirst()
            val name = cursor.getString(nameIndex)
            File(context.filesDir, name).path
        }
    }!!
    return PlatformFile(d)
}