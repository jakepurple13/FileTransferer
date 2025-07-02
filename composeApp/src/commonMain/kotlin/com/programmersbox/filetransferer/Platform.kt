package com.programmersbox.filetransferer

import com.programmersbox.filetransferer.net.ILog

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