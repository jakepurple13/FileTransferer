package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadFilesReq(
    val downloadFiles: List<FileExploreFile>,
    val bufferSize: Int
)