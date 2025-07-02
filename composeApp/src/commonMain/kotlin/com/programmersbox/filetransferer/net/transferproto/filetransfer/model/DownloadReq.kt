package com.programmersbox.filetransferer.net.transferproto.filetransfer.model

import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import kotlinx.serialization.Serializable

@Serializable
data class DownloadReq(
    val file: FileExploreFile,
    val start: Long,
    val end: Long
)