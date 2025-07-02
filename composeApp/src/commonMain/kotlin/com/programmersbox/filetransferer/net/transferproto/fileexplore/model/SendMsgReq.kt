package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class SendMsgReq(
    val sendTime: Long,
    val msg: String
)