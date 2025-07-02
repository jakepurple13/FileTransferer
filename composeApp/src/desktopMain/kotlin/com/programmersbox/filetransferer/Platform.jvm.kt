package com.programmersbox.filetransferer

import io.github.vinceglb.filekit.PlatformFile

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun getDefaultDownloadDir(): String {
    TODO("Not yet implemented")
}

actual fun readPlatformFile(file: PlatformFile): PlatformFile = file