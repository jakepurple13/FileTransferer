package com.programmersbox.filetransferer.net.transferproto.broadcastconn.model

import kotlinx.serialization.Serializable
import androidx.annotation.Keep
import com.squareup.moshi.JsonClass

@Keep
@JsonClass(generateAdapter = true)
@Serializable
data class BroadcastTransferFileResp(
    val deviceName: String
)