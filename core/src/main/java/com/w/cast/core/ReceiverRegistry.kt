package com.w.cast.core

import android.view.Surface

class ReceiverRegistry(
    private val receivers: List<CastableReceiver>
) {
    fun startAll(): List<CastableReceiver> = receivers.filter { it.start() }

    fun stopAll() {
        receivers.forEach(CastableReceiver::stop)
    }

    fun setVideoSurface(surface: Surface?) {
        receivers.forEach { it.setVideoSurface(surface) }
    }

    fun all(): List<CastableReceiver> = receivers
}
