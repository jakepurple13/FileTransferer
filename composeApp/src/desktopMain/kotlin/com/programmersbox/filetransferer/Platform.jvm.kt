package com.programmersbox.filetransferer

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun getDefaultDownloadDir(): String {
    TODO("Not yet implemented")
}