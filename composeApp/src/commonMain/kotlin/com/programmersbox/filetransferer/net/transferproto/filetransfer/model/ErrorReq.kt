package com.programmersbox.filetransferer.net.transferproto.filetransfer.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorReq(
    val errorMsg: String
)