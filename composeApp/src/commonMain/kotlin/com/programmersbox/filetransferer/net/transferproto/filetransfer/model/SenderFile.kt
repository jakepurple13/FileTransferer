package com.programmersbox.filetransferer.net.transferproto.filetransfer.model

import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.FileExploreFile
import java.io.File

data class SenderFile(
    val realFile: File,
    val exploreFile: FileExploreFile
)