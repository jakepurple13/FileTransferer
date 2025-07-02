package com.programmersbox.filetransferer.net.netty.extensions

import com.programmersbox.filetransferer.net.netty.PackageData

interface IPackageDataConverter {

    fun couldHandle(type: Int, dataClass: Class<*>): Boolean

    fun <T> convert(type: Int, messageId: Long, data: T, dataClass: Class<T>): PackageData?
}