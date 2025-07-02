package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class SendFilesReq(
    val sendFiles: List<FileExploreFile>,
    val maxConnection: Int
)