package com.w.cast

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppLog {
    private const val maxEntries = 300
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private val listeners = mutableSetOf<(String) -> Unit>()

    fun add(message: String) {
        val line = "${timestamp()} $message"
        val currentListeners = synchronized(lock) {
            if (entries.size >= maxEntries) entries.removeFirst()
            entries.addLast(line)
            listeners.toList()
        }
        currentListeners.forEach { listener -> listener(line) }
    }

    fun subscribe(listener: (String) -> Unit): List<String> = synchronized(lock) {
        listeners += listener
        entries.toList()
    }

    fun unsubscribe(listener: (String) -> Unit) {
        synchronized(lock) {
            listeners -= listener
        }
    }

    private fun timestamp(): String = synchronized(lock) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    }
}
