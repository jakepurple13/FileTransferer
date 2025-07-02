package com.programmersbox.filetransferer.net.transferproto.filetransfer.model

import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import kotlinx.serialization.Serializable
import androidx.annotation.Keep
import com.squareup.moshi.JsonClass

@Keep
@JsonClass(generateAdapter = true)
@Serializable
data class DownloadReq(
    val file: FileExploreFile,
    val start: Long,
    val end: Long
)