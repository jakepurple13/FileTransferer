package com.programmersbox.filetransferer.net.netty.handlers

import com.programmersbox.filetransferer.net.netty.PackageData
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder

class PackageDataToBytesEncoder : MessageToByteEncoder<PackageData>() {

    override fun encode(ctx: ChannelHandlerContext, msg: PackageData, out: ByteBuf) {
        out.writeInt(msg.type)
        out.writeLong(msg.messageId)
        out.writeBytes(msg.body)
    }
}