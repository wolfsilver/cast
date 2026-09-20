package com.w.cast.caststub

import android.view.Surface
import android.util.Log
import com.w.cast.core.CastableReceiver
import com.w.cast.core.ReceiverStatus

class GoogleCastReceiver : CastableReceiver {
    override val id: String = "google-cast"
    override val displayName: String = "Google Cast"
    override val status: ReceiverStatus = ReceiverStatus.UNAVAILABLE

    override fun start(): Boolean {
        Log.i(id, "Google Cast receiver is reserved for a future certified Cast implementation")
        return false
    }

    override fun stop() = Unit

    override fun setVideoSurface(surface: Surface?) = Unit
}
