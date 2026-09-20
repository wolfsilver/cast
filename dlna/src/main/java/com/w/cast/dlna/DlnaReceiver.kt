package com.w.cast.dlna

import android.content.Context
import android.util.Log
import android.view.Surface
import com.w.cast.core.CastableReceiver
import com.w.cast.core.ReceiverStatus

class DlnaReceiver(
    context: Context
) : CastableReceiver {
    override val id: String = "dlna"
    override val displayName: String = "DLNA MediaRenderer"
    override var status: ReceiverStatus = ReceiverStatus.STOPPED
        private set

    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    override fun start(): Boolean {
        status = ReceiverStatus.STARTING
        Log.i(id, "DLNA MediaRenderer service is reserved for the UPnP transport implementation")
        status = ReceiverStatus.UNAVAILABLE
        return false
    }

    override fun stop() {
        status = ReceiverStatus.STOPPED
    }

    override fun setVideoSurface(surface: Surface?) = Unit
}
