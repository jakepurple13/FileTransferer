package com.programmersbox.filetransferer.net.netty.extensions

import com.programmersbox.filetransferer.net.netty.PackageData

interface IBodyConverter {

    fun couldHandle(type: Int, dataClass: Class<*>): Boolean

    fun <T> convert(type: Int, dataClass: Class<T>, packageData: PackageData): T?
}
