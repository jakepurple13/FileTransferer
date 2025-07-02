package com.programmersbox.filetransferer.net.transferproto.qrscanconn.model

import androidx.annotation.Keep
import com.squareup.moshi.JsonClass
import kotlinx.serialization.Serializable

@Serializable
@Keep
@JsonClass(generateAdapter = true)
data class QRCodeTransferFileReq(
    val version: Int,
    val deviceName: String
)