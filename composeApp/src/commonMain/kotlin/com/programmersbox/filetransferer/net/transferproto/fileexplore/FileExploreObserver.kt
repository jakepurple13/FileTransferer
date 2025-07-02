package com.programmersbox.filetransferer.net.transferproto.fileexplore

import com.programmersbox.filetransferer.net.transferproto.fileexplore.model.SendMsgReq

interface FileExploreObserver {

    fun onNewState(state: FileExploreState)

    fun onNewMsg(msg: SendMsgReq)
}