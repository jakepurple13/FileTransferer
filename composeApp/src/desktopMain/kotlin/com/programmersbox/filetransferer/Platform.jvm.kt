package com.programmersbox.filetransferer

import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.downloadDir
import io.github.vinceglb.filekit.path

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun getDefaultDownloadDir(): String = FileKit.downloadDir.path

actual fun readPlatformFile(file: PlatformFile): PlatformFile = file

actual fun toFileExplore(file: PlatformFile): FileExploreFile {
    TODO("Not yet implemented")
}