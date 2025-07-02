package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class FileExploreFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModify: Long
)