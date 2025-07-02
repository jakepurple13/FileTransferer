package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class HandshakeReq(
    val version: Int,
    val fileSeparator: String
)
