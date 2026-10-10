package io.github.nugggy.pluslock.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory diary of what the app did, for the Diagnostics screen.
 * Nothing is written to disk or sent anywhere; it's gone when the app restarts.
 */
object Diag {
    data class Entry(val time: Long, val area: String, val text: String)

    private const val MAX = 300
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun log(area: String, text: String) {
        entries.addLast(Entry(System.currentTimeMillis(), area, text))
        while (entries.size > MAX) entries.removeFirst()
    }

    @Synchronized
    fun all(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    fun format(e: Entry): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(e.time)) + "  [" + e.area + "] " + e.text
}
