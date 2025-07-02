package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class HandshakeResp(
    val fileSeparator: String
)
