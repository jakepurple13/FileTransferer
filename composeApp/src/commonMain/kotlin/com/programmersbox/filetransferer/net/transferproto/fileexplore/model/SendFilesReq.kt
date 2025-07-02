package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable
import androidx.annotation.Keep
import com.squareup.moshi.JsonClass

@Keep
@JsonClass(generateAdapter = true)
@Serializable
data class SendFilesReq(
    val sendFiles: List<FileExploreFile>,
    val maxConnection: Int
)