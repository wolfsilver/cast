package com.w.cast.core

import android.view.Surface

interface CastableReceiver {
    val id: String
    val displayName: String
    val status: ReceiverStatus

    fun start(): Boolean
    fun stop()
    fun setVideoSurface(surface: Surface?)
}

enum class ReceiverStatus {
    STOPPED,
    STARTING,
    RUNNING,
    UNAVAILABLE,
    ERROR
}
