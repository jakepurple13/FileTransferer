package com.programmersbox.filetransferer.net.transferproto.fileexplore.model

import kotlinx.serialization.Serializable

@Serializable
data class ScanDirResp(
    val path: String,
    val childrenDirs: List<FileExploreDir>,
    val childrenFiles: List<FileExploreFile>
)