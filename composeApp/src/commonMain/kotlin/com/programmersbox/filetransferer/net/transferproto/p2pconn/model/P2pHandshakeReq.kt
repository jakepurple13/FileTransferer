package com.programmersbox.filetransferer.net.transferproto.p2pconn.model

import androidx.annotation.Keep
import com.squareup.moshi.JsonClass
import kotlinx.serialization.Serializable

@Serializable
@Keep
@JsonClass(generateAdapter = true)
data class P2pHandshakeReq(
    val version: Int,
    val deviceName: String
)