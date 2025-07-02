package com.programmersbox.filetransferer.net.transferproto

interface SimpleCallback<T> {

    fun onError(errorMsg: String) {}

    fun onSuccess(data: T) {}
}