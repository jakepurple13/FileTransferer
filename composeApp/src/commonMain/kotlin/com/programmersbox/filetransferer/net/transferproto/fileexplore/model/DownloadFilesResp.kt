package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadFilesResp(
    val maxConnection: Int
)