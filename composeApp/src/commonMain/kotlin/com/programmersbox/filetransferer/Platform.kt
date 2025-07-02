package com.programmersbox.filetransferer

import com.programmersbox.filetransferer.net.ILog
import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

object DefaultLogger : ILog {
    override fun d(tag: String, msg: String) {
        println("$tag: $msg")
    }

    override fun e(tag: String, msg: String, throwable: Throwable?) {
        println("$tag: $msg, throwable: $throwable")
    }
}

expect fun getDefaultDownloadDir(): String

expect fun readPlatformFile(file: PlatformFile): PlatformFile

expect fun toFileExplore(file: PlatformFile): FileExploreFile